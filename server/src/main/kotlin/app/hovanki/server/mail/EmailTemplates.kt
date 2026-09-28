package app.hovanki.server.mail

import app.hovanki.shared.rules.AccountRules
import java.time.Duration

/**
 * The emails with a code, in the account's language (en, ru, uk; anything else gets English). Plain text, the code on a
 * line of its own. Nothing the user typed goes into an email (no nickname): an address someone else entered must not
 * receive their text. The confirmation email says that confirming is optional: the account works without it.
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
        val text = listOfNotNull(
            template.greeting,
            template.intro,
            "    $code",
            template.validity,
            template.note,
            template.ignore,
            SIGNATURE,
        ).joinToString("\n\n", postfix = "\n")
        return OutgoingEmail(to, purpose, lang, template.subject, text, code)
    }

    private class Template(
        val subject: String,
        val greeting: String,
        val intro: String,
        val validity: String,
        val ignore: String,
        /** What the code is good for, where that isn't obvious. */
        val note: String? = null,
    )

    private const val SIGNATURE = "— Hovanki"

    private fun english(purpose: EmailPurpose, minutes: Long): Template {
        val valid = "The code is valid for $minutes ${if (minutes == 1L) "minute" else "minutes"}."
        return when (purpose) {
            EmailPurpose.VERIFY_EMAIL -> Template(
                subject = "Your Hovanki email confirmation code",
                greeting = "Hello!",
                intro = "Here is your code to confirm your email in Hovanki:",
                validity = "Enter it in the app. $valid",
                note = "Confirming is optional, you can play without it. It shows that this address reaches you, " +
                    "so you can count on it if you ever need to reset your password.",
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

            EmailPurpose.STAFF_ENROLL -> Template(
                subject = "Hovanki admin: set up your authenticator",
                greeting = "Hello!",
                intro = "Here is your code to set up the authenticator app for the Hovanki admin:",
                validity = "Enter it on the admin page. $valid",
                ignore = "If you didn't just log in to the Hovanki admin, someone knows your password: " +
                    "change it in the app right away and tell the other admins.",
            )
        }
    }

    private fun russian(purpose: EmailPurpose, minutes: Long): Template {
        val valid = "Код действует $minutes ${plural(minutes, "минуту", "минуты", "минут")}."
        return when (purpose) {
            EmailPurpose.VERIFY_EMAIL -> Template(
                subject = "Код подтверждения email для Hovanki",
                greeting = "Здравствуйте!",
                intro = "Ваш код для подтверждения email в Hovanki:",
                validity = "Введите его в приложении. $valid",
                note = "Подтверждать адрес необязательно, играть можно и без этого. Подтверждение показывает, " +
                    "что письма на этот адрес до вас доходят, а значит, через него можно будет восстановить пароль.",
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

            EmailPurpose.STAFF_ENROLL -> Template(
                subject = "Админка Hovanki: подключение аутентификатора",
                greeting = "Здравствуйте!",
                intro = "Ваш код для подключения приложения-аутентификатора к админке Hovanki:",
                validity = "Введите его на странице админки. $valid",
                ignore = "Если вы только что не входили в админку Hovanki, кто-то знает ваш пароль: " +
                    "сразу смените его в приложении и сообщите другим админам.",
            )
        }
    }

    private fun ukrainian(purpose: EmailPurpose, minutes: Long): Template {
        val valid = "Код діє $minutes ${plural(minutes, "хвилину", "хвилини", "хвилин")}."
        return when (purpose) {
            EmailPurpose.VERIFY_EMAIL -> Template(
                subject = "Код підтвердження email для Hovanki",
                greeting = "Вітаємо!",
                intro = "Ваш код для підтвердження email у Hovanki:",
                validity = "Введіть його в застосунку. $valid",
                note = "Підтверджувати адресу необов’язково, грати можна й без цього. Підтвердження показує, " +
                    "що листи на цю адресу до вас доходять, тож через неї можна буде відновити пароль.",
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

            EmailPurpose.STAFF_ENROLL -> Template(
                subject = "Адмінка Hovanki: підключення автентифікатора",
                greeting = "Вітаємо!",
                intro = "Ваш код для підключення застосунку-автентифікатора до адмінки Hovanki:",
                validity = "Введіть його на сторінці адмінки. $valid",
                ignore = "Якщо ви щойно не входили в адмінку Hovanki, хтось знає ваш пароль: " +
                    "одразу змініть його в застосунку та повідомте інших адмінів.",
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
