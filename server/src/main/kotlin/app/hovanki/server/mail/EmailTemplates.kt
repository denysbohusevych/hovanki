package app.hovanki.server.mail

import app.hovanki.shared.rules.AccountRules
import java.time.Duration

/**
 * The emails with a code, in the account's language (en, ru, uk; anything else gets English). Plain text, the code on a
 * line of its own. Nothing the user typed goes into an email (no nickname): an address someone else entered must not
 * receive their text.
 */
object EmailTemplates {
    fun render(to: String, purpose: EmailPurpose, language: String, code: String, validFor: Duration): OutgoingEmail {
        val lang = AccountRules.language(language)
        val minutes = validFor.toMinutes().coerceAtLeast(1)
        val template = when (lang) {
            "ru" -> russian(purpose, minutes)
            "uk" -> ukrainian(purpose, minutes)
            else -> english(purpose, minutes)
        }
        val text = listOf(template.greeting, template.intro, "    $code", template.validity, template.ignore, SIGNATURE)
            .joinToString("\n\n", postfix = "\n")
        return OutgoingEmail(to, purpose, lang, template.subject, text, code)
    }

    private class Template(
        val subject: String,
        val greeting: String,
        val intro: String,
        val validity: String,
        val ignore: String,
    )

    private const val SIGNATURE = "— Hovanki"

    private fun english(purpose: EmailPurpose, minutes: Long): Template {
        val valid = "The code is valid for $minutes ${if (minutes == 1L) "minute" else "minutes"}."
        return when (purpose) {
            EmailPurpose.VERIFY_EMAIL -> Template(
                subject = "Confirm your email for Hovanki",
                greeting = "Hello!",
                intro = "Here is your code to confirm your email in Hovanki:",
                validity = "Enter it in the app. $valid",
                ignore = "If you didn't sign up for Hovanki, just ignore this email.",
            )

            EmailPurpose.RESET_PASSWORD -> Template(
                subject = "Reset your Hovanki password",
                greeting = "Hello!",
                intro = "Here is your code to reset your Hovanki password:",
                validity = "Enter it in the app together with your new password. $valid",
                ignore = "If you didn't ask to reset your password, just ignore this email: " +
                    "your password stays the same.",
            )
        }
    }

    private fun russian(purpose: EmailPurpose, minutes: Long): Template {
        val valid = "Код действует $minutes ${plural(minutes, "минуту", "минуты", "минут")}."
        return when (purpose) {
            EmailPurpose.VERIFY_EMAIL -> Template(
                subject = "Подтвердите email в Hovanki",
                greeting = "Здравствуйте!",
                intro = "Ваш код для подтверждения email в Hovanki:",
                validity = "Введите его в приложении. $valid",
                ignore = "Если вы не регистрировались в Hovanki, просто проигнорируйте это письмо.",
            )

            EmailPurpose.RESET_PASSWORD -> Template(
                subject = "Сброс пароля в Hovanki",
                greeting = "Здравствуйте!",
                intro = "Ваш код для сброса пароля в Hovanki:",
                validity = "Введите его в приложении вместе с новым паролем. $valid",
                ignore = "Если вы не запрашивали сброс пароля, просто проигнорируйте это письмо: " +
                    "пароль останется прежним.",
            )
        }
    }

    private fun ukrainian(purpose: EmailPurpose, minutes: Long): Template {
        val valid = "Код діє $minutes ${plural(minutes, "хвилину", "хвилини", "хвилин")}."
        return when (purpose) {
            EmailPurpose.VERIFY_EMAIL -> Template(
                subject = "Підтвердіть email у Hovanki",
                greeting = "Вітаємо!",
                intro = "Ваш код для підтвердження email у Hovanki:",
                validity = "Введіть його в застосунку. $valid",
                ignore = "Якщо ви не реєструвалися в Hovanki, просто проігноруйте цей лист.",
            )

            EmailPurpose.RESET_PASSWORD -> Template(
                subject = "Скидання пароля в Hovanki",
                greeting = "Вітаємо!",
                intro = "Ваш код для скидання пароля в Hovanki:",
                validity = "Введіть його в застосунку разом із новим паролем. $valid",
                ignore = "Якщо ви не просили скинути пароль, просто проігноруйте цей лист: " +
                    "пароль залишиться тим самим.",
            )
        }
    }

    /** Russian and Ukrainian plural forms: 1 минуту, 2 минуты, 5 минут, 21 минуту. */
    private fun plural(n: Long, one: String, few: String, many: String): String = when {
        n % 10 == 1L && n % 100 != 11L -> one
        n % 10 in 2L..4L && n % 100 !in 12L..14L -> few
        else -> many
    }
}
