package fraudguard.world

import fraudguard.core.Claim
import java.util.SplittableRandom

/** Appeal messages players send, by what they claim and in their language. Generated, so readers are tested on templates. */
object Texts {
    private val T: Map<String, Map<Claim, List<String>>> = mapOf(
        "en" to mapOf(
            Claim.FAMILY_SHARES_DEVICE to listOf("Why is my cash out pending?? My son plays on the same phone, we each have our own account.", "My wife and I share the phone, that's not cheating. Please release my payment."),
            Claim.VPN_FOR_PRIVACY to listOf("I use a VPN for privacy on every app, I'm in the US. Where is my PayPal money?", "Is this because of my VPN? I only use it for privacy."),
            Claim.NEW_PHONE to listOf("I got a new phone and logged in again, now my cash out is stuck.", "I changed phone last week. Please check, the account is mine."),
            Claim.TRAVELLING to listOf("I'm on vacation abroad this week, I still want my reward.", "I was travelling for work, why is my withdrawal on hold?"),
            Claim.NO_REASON to listOf("This is a scam. I played for weeks and you won't pay me.", "Where is my money? Pay me now."),
        ),
        "es" to mapOf(
            Claim.FAMILY_SHARES_DEVICE to listOf("¿Por qué mi retiro está pendiente? Mi hijo juega en el mismo teléfono, cada uno tiene su cuenta."),
            Claim.VPN_FOR_PRIVACY to listOf("Uso una VPN por privacidad. ¿Por qué no puedo cobrar mi pago?"),
            Claim.NEW_PHONE to listOf("Tengo un teléfono nuevo y mi retiro no llega a mi cuenta."),
            Claim.TRAVELLING to listOf("Estoy de viaje esta semana, por qué mi pago no llega a mi cuenta?"),
            Claim.NO_REASON to listOf("¿Dónde está mi pago? Jugué muchas semanas y no puedo retirar de mi cuenta."),
        ),
        "pt" to mapOf(
            Claim.FAMILY_SHARES_DEVICE to listOf("Por que meu saque está pendente? Meu filho joga no mesmo celular, cada um tem sua conta."),
            Claim.VPN_FOR_PRIVACY to listOf("Eu uso VPN por privacidade. Por que meu pagamento não caiu na minha conta?"),
            Claim.NEW_PHONE to listOf("Comprei um celular novo e meu saque não caiu na minha conta."),
            Claim.TRAVELLING to listOf("Estou de férias fora do país, por que meu saque não caiu na minha conta?"),
            Claim.NO_REASON to listOf("Cadê meu pagamento? Joguei semanas e não consigo sacar da minha conta."),
        ),
        "de" to mapOf(
            Claim.FAMILY_SHARES_DEVICE to listOf("Warum ist meine Auszahlung blockiert? Mein Sohn spielt auf dem gleichen Handy, wir haben jeder ein Konto."),
            Claim.VPN_FOR_PRIVACY to listOf("Ich nutze ein VPN wegen Datenschutz. Warum kommt meine Auszahlung nicht auf mein Konto?"),
            Claim.NEW_PHONE to listOf("Ich habe ein neues Handy und meine Auszahlung ist nicht auf meinem Konto."),
            Claim.TRAVELLING to listOf("Ich bin im Urlaub, warum ist meine Auszahlung nicht auf meinem Konto?"),
            Claim.NO_REASON to listOf("Wo ist mein Geld? Ich habe wochenlang gespielt und meine Auszahlung kommt nicht."),
        ),
    )

    fun appeal(claim: Claim, language: String, r: SplittableRandom): String {
        val options = T[language]?.get(claim) ?: T.getValue("en").getValue(claim)
        return options[r.nextInt(options.size)]
    }
}
