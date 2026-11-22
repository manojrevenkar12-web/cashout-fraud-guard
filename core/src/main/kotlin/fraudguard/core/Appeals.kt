package fraudguard.core

/** What a player says when they ask why a cash-out is on hold. */
enum class Claim { FAMILY_SHARES_DEVICE, VPN_FOR_PRIVACY, NEW_PHONE, TRAVELLING, NO_REASON }

data class AppealReading(val language: String, val claim: Claim)

/**
 * Reads a player's appeal. The reading is data: it may route the case to an analyst sooner, with the claim attached,
 * but it never pays, unblocks or reveals a check. A model can replace [RuleAppealReader] if it returns the same
 * [AppealReading] and passes the same tests.
 */
interface AppealReader {
    fun read(text: String): AppealReading
}

object RuleAppealReader : AppealReader {
    private val LANG = mapOf(
        "de" to listOf("ich", "mein", "nicht", "auszahlung", "warum", "konto"),
        "es" to listOf("mi ", "por qué", "porque", "cuenta", "retiro", "pago", "no puedo"),
        "pt" to listOf("meu", "minha", "conta", "saque", "pagamento", "não", "por que"),
    )
    private val CLAIMS = linkedMapOf(
        Claim.FAMILY_SHARES_DEVICE to listOf(
            "my son", "my daughter", "my wife", "my husband", "my kid", "my brother", "my sister", "my mom", "same phone", "share the phone", "family",
            "mi hijo", "mi hija", "mi esposa", "mi esposo", "mismo teléfono", "familia", "meu filho", "minha filha", "minha esposa", "mesmo celular", "família",
            "mein sohn", "meine tochter", "meine frau", "mein mann", "gleiche handy", "familie",
        ),
        Claim.VPN_FOR_PRIVACY to listOf("vpn", "privacy", "privacidad", "privacidade", "datenschutz"),
        Claim.NEW_PHONE to listOf("new phone", "changed phone", "reset my phone", "teléfono nuevo", "celular novo", "neues handy", "factory reset"),
        Claim.TRAVELLING to listOf("travel", "vacation", "abroad", "trip", "viaje", "vacaciones", "viagem", "férias", "urlaub", "reise"),
    )

    override fun read(text: String): AppealReading {
        val t = text.lowercase()
        val lang = LANG.entries.maxByOrNull { (_, ws) -> ws.count { it in t } }?.takeIf { (_, ws) -> ws.count { it in t } >= 2 }?.key ?: "en"
        val claim = CLAIMS.entries.firstOrNull { (_, ws) -> ws.any { it in t } }?.key ?: Claim.NO_REASON
        return AppealReading(lang, claim)
    }
}

object Appeals {
    /** Evidence no claim explains away: a reused or changed face, a confirmed ring, an emulator, scripted play. */
    val HARD = setOf(Reason.FACE_REUSED, Reason.FACE_CHANGED, Reason.RING_CONFIRMED, Reason.EMULATOR, Reason.OFFER_SPEED, Reason.SCRIPTED_TIMING)

    /**
     * Whether a claim, if true, would explain why the case was held, with no hard evidence against it. Only then does
     * it earn an earlier review.
     */
    fun explains(claim: Claim, reasons: Collection<Reason>): Boolean = reasons.none { it in HARD } && when (claim) {
        Claim.FAMILY_SHARES_DEVICE -> reasons.any { it == Reason.SHARED_DEVICE || it == Reason.SHARED_PAYOUT || it == Reason.RING }
        Claim.VPN_FOR_PRIVACY -> reasons.any { it == Reason.VPN_OR_DATACENTER || it == Reason.DATACENTER_NEIGHBOURS }
        Claim.NEW_PHONE -> reasons.any { it == Reason.SHARED_DEVICE || it == Reason.WEAK_DEVICE || it == Reason.PAYOUT_SWITCH }
        Claim.TRAVELLING -> reasons.any { it == Reason.GEO_MISMATCH || it == Reason.VPN_OR_DATACENTER }
        Claim.NO_REASON -> false
    }

    private val REPLY = mapOf(
        "en" to "Thanks for writing. Your cash-out is with our review team%s. We check cash-outs to keep rewards fair for every player, and we can't share the details of our checks. You'll hear from us once the review is done.",
        "es" to "Gracias por escribirnos. Tu retiro está con nuestro equipo de revisión%s. Revisamos los retiros para que las recompensas sean justas para todos, y no podemos compartir los detalles. Te avisaremos cuando termine la revisión.",
        "pt" to "Obrigado por escrever. Seu saque está com nossa equipe de revisão%s. Revisamos os saques para manter as recompensas justas para todos e não podemos compartilhar os detalhes. Avisaremos quando a revisão terminar.",
        "de" to "Danke für deine Nachricht. Deine Auszahlung liegt bei unserem Prüfteam%s. Wir prüfen Auszahlungen, damit Belohnungen für alle fair bleiben, und können keine Details nennen. Wir melden uns, sobald die Prüfung abgeschlossen ist.",
    )
    private val SOONER = mapOf("en" to ", and we've added what you told us", "es" to ", y añadimos lo que nos contaste", "pt" to ", e adicionamos o que você nos contou", "de" to ", und wir haben deine Angaben ergänzt")

    /** The reply draft in the player's language. It never names a signal, a score or a rule. */
    fun reply(reading: AppealReading, requeued: Boolean): String {
        val lang = if (reading.language in REPLY) reading.language else "en"
        return REPLY[lang]!!.format(if (requeued) SOONER[lang]!! else "")
    }
}
