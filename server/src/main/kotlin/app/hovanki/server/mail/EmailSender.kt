package app.hovanki.server.mail

/** What an email with a code is for; stored as text in `email_codes.purpose`. */
enum class EmailPurpose { VERIFY_EMAIL, RESET_PASSWORD }

/** A rendered email, plain text. [code] is kept separately for the recording sender (tests read it there). */
data class OutgoingEmail(
    val to: String,
    val purpose: EmailPurpose,
    val language: String,
    val subject: String,
    val text: String,
    val code: String,
) {
    // Never the address or the code in logs.
    override fun toString(): String = "OutgoingEmail($purpose, $language)"
}

/** Delivers emails; picked by `hovanki.mail.sender` ([MailConfig]). Called off the request thread ([Mailer]). */
fun interface EmailSender {
    fun send(email: OutgoingEmail)
}
