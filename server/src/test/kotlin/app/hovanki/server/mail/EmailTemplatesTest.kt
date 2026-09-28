package app.hovanki.server.mail

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailTemplatesTest {
    private val fifteenMinutes = Duration.ofMinutes(15)

    @Test
    fun everyLanguageAndPurpose() {
        val validity = mapOf("en" to "15 minutes", "ru" to "15 минут", "uk" to "15 хвилин")
        val subjects = mutableSetOf<String>()
        for ((language, minutes) in validity) {
            for (purpose in EmailPurpose.entries) {
                val email = EmailTemplates.render("bob@example.com", purpose, language, "042917", fifteenMinutes)
                assertEquals("bob@example.com", email.to)
                assertEquals(purpose, email.purpose)
                assertEquals(language, email.language)
                assertEquals("042917", email.code)
                // The code on a line of its own, where the eye finds it.
                assertContains(email.text, "\n    042917\n")
                assertContains(email.text, minutes)
                assertContains(email.subject, "Hovanki")
                subjects += email.subject
            }
        }
        assertEquals(validity.size * EmailPurpose.entries.size, subjects.size, "$subjects")
    }

    @Test
    fun theTextsSayWhatToDoAndWhatIfItWasNotYou() {
        val verify = EmailTemplates.render("a@b.co", EmailPurpose.VERIFY_EMAIL, "ru", "123456", fifteenMinutes)
        assertContains(verify.text, "Введите его в приложении")
        assertContains(verify.text, "не регистрировались")
        val reset = EmailTemplates.render("a@b.co", EmailPurpose.RESET_PASSWORD, "uk", "123456", fifteenMinutes)
        assertContains(reset.text, "разом із новим паролем")
        assertContains(reset.text, "пароль залишиться тим самим")
        val english = EmailTemplates.render("a@b.co", EmailPurpose.RESET_PASSWORD, "en", "123456", fifteenMinutes)
        assertContains(english.text, "just ignore this email")
        // Staff who didn't log in: someone knows their password (docs/adr/0008-admin.md).
        val staff = EmailTemplates.render("a@b.co", EmailPurpose.STAFF_ENROLL, "ru", "123456", fifteenMinutes)
        assertContains(staff.text, "странице админки")
        assertContains(staff.text, "кто-то знает ваш пароль")
    }

    @Test
    fun confirmingTheEmailIsOptionalAndHelpsWithThePassword() {
        val optional = mapOf(
            "en" to listOf("Confirming is optional", "reset your password"),
            "ru" to listOf("Подтверждать адрес необязательно", "восстановить пароль"),
            "uk" to listOf("Підтверджувати адресу необов’язково", "відновити пароль"),
        )
        for ((language, phrases) in optional) {
            val verify = EmailTemplates.render("a@b.co", EmailPurpose.VERIFY_EMAIL, language, "123456", fifteenMinutes)
            for (phrase in phrases) assertContains(verify.text, phrase)
            // Only in the confirmation email.
            val reset = EmailTemplates.render("a@b.co", EmailPurpose.RESET_PASSWORD, language, "123456", fifteenMinutes)
            assertTrue(phrases.first() !in reset.text, reset.text)
        }
    }

    @Test
    fun otherLanguagesGetEnglish() {
        assertEquals("en", render("de").language)
        assertEquals("en", render("").language)
        assertEquals("ru", render("ru-RU").language)
        assertEquals("uk", render("UK").language)
    }

    @Test
    fun pluralsOfMinutes() {
        val russian = mapOf(1 to "1 минуту", 2 to "2 минуты", 5 to "5 минут", 11 to "11 минут", 21 to "21 минуту")
        for ((minutes, text) in russian) assertContains(render("ru", minutes).text, "действует $text.")
        val ukrainian = mapOf(1 to "1 хвилину", 3 to "3 хвилини", 12 to "12 хвилин", 22 to "22 хвилини")
        for ((minutes, text) in ukrainian) assertContains(render("uk", minutes).text, "діє $text.")
        assertContains(render("en", 1).text, "valid for 1 minute.")
    }

    @Test
    fun plainTextOnly() {
        for (language in listOf("en", "ru", "uk")) {
            val text = render(language).text
            assertTrue('<' !in text && "http" !in text, text)
        }
    }

    private fun render(language: String, minutes: Int = 15) = EmailTemplates.render(
        "a@b.co",
        EmailPurpose.VERIFY_EMAIL,
        language,
        "123456",
        Duration.ofMinutes(minutes.toLong()),
    )
}
