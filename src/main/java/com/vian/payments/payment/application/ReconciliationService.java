package com.vian.payments.payment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.vian.payments.config.AppProperties;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.port.in.ReconcilePaymentsUseCase;
import com.vian.payments.payment.port.out.PartnerChargeCommand;
import com.vian.payments.payment.port.out.PartnerClient;
import com.vian.payments.payment.port.out.PartnerPaymentResult;
import com.vian.payments.payment.port.out.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reconciliation worker: menyelesaikan transaksi yang tidak berakhir normal.
 *
 * Dua kasus, dan penanganannya berbeda:
 *
 * - IN_DOUBT: charge sudah dikirim tapi hasilnya tidak diketahui (timeout, koneksi
 *   putus, partner balas 5xx). JANGAN retry charge, tanyakan statusnya pakai
 *   reference kita yang jadi idempotency key di sisi partner.
 * - PENDING yang menggantung: proses mati setelah payment disimpan tapi sebelum
 *   pemanggilan partner selesai. Charge-nya mungkin belum pernah terkirim, jadi
 *   yang benar adalah mengirimkannya. Kalau ternyata sudah terkirim, reference
 *   yang sama membuat partner menolak membuat transaksi kedua.
 */
@Service
public class ReconciliationService implements ReconcilePaymentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int BATCH_SIZE = 50;

    private final PaymentRepository payments;
    private final PaymentStateWriter paymentStateWriter;
    private final PartnerClient partnerClient;
    private final AppProperties properties;
    private final Clock clock;

    public ReconciliationService(PaymentRepository payments,
                                 PaymentStateWriter paymentStateWriter,
                                 PartnerClient partnerClient,
                                 AppProperties properties,
                                 Clock clock) {
        this.payments = payments;
        this.paymentStateWriter = paymentStateWriter;
        this.partnerClient = partnerClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int reconcile() {
        Instant now = clock.instant();
        int pending = recoverStalePending(now);
        int inDoubt = resolveInDoubt(now);

        if (pending > 0 || inDoubt > 0) {
            log.info("Reconciliation selesai: pendingRecovered={}, inDoubtResolved={}", pending, inDoubt);
        }
        return pending + inDoubt;
    }

    /**
     * Charge ulang payment yang tertahan di PENDING. Kalau prosesnya mati setelah
     * partner menerima charge tapi sebelum state tersimpan, partner akan menolak
     * karena reference-nya sama -- tidak ada tagihan kedua.
     */
    private int recoverStalePending(Instant now) {
        Instant cutoff = now.minus(properties.reconciliation().pendingAfter());
        List<Payment> candidates = payments.findStalePending(cutoff, BATCH_SIZE);
        int recovered = 0;

        for (Payment payment : candidates) {
            try {
                PartnerPaymentResult result = partnerClient.charge(new PartnerChargeCommand(
                        payment.reference(),
                        payment.money().amount(),
                        payment.money().currency(),
                        payment.customerId()
                ));
                if (applyAndSave(payment, result)) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                // Partner tidak terjangkau: biarkan tetap PENDING, siklus berikutnya mencoba lagi.
                log.warn("Recovery PENDING gagal untuk {}", payment.reference(), e);
            }
        }
        return recovered;
    }

    private int resolveInDoubt(Instant now) {
        Instant cutoff = now.minus(properties.reconciliation().inDoubtAfter());
        List<Payment> candidates = payments.findStaleInDoubt(cutoff, BATCH_SIZE);
        int resolved = 0;

        for (Payment payment : candidates) {
            try {
                PartnerPaymentResult result = partnerClient.queryStatus(payment.reference());
                if (result.status() == PartnerPaymentResult.Status.UNKNOWN) {
                    log.info("Reconciliation: {} masih UNKNOWN", payment.reference());
                    continue;
                }
                if (applyAndSave(payment, result)) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                // Satu payment gagal tidak boleh menggagalkan seluruh batch.
                log.warn("Reconciliation gagal untuk {}", payment.reference(), e);
            }
        }
        return resolved;
    }

    private boolean applyAndSave(Payment payment, PartnerPaymentResult result) {
        boolean changed = switch (result.status()) {
            case SUCCEEDED -> payment.markSucceeded(result.partnerReference());
            case DECLINED -> payment.markFailed(
                    result.reason() == null ? "DECLINED" : result.reason());
            case UNKNOWN -> payment.markInDoubt(
                    result.reason() == null ? "UNKNOWN" : result.reason());
        };
        if (changed) {
            paymentStateWriter.saveAndPublish(payment);
        }
        return changed;
    }
}
