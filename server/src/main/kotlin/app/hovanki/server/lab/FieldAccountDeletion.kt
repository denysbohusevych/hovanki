package app.hovanki.server.lab

import app.hovanki.server.account.BeforeAccountDeletion
import app.hovanki.shared.protocol.UserId
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * A deleted account takes its field log along (CLAUDE.md, GDPR): its devices and chunks go by `ON DELETE CASCADE`, and
 * the reports of the games it played in were built from them, its survey and marks too. Inside the deletion's
 * transaction the stored reports go; once it committed, the live builders are forgotten and the reports computed again
 * from the logs that remain.
 */
@Component
class FieldAccountDeletion(private val repository: LabRunRepository, private val reports: LabReportWriter) :
    BeforeAccountDeletion {
    override fun beforeDelete(userId: UserId) {
        val runs = repository.gameRunsOfUser(userId.value)
        if (runs.isEmpty()) return
        for (runId in runs) repository.deleteReport(runId)
        val recompute = {
            for (runId in runs) {
                reports.forget(runId)
                reports.compute(runId)
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = recompute()
                },
            )
        } else {
            recompute()
        }
    }
}
