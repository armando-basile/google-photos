package dev.armando.tvphotos.auth

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

/** Server HTTP temporaneo in LAN che riceve il codice OAuth inoltrato dal telefono. */
class PairingServer(
    port: Int,
    private val nonce: String,
    private val onResult: (Result<String>) -> Unit,
) : NanoHTTPD(port) {

    private var done = false

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET || session.uri != "/callback") {
            return html(Response.Status.NOT_FOUND, "Pagina non trovata.")
        }
        val params = session.parameters
        if (params["state"]?.firstOrNull() != nonce) {
            return html(Response.Status.BAD_REQUEST, "Richiesta non valida.")
        }
        synchronized(this) {
            if (done) return html(Response.Status.OK, "Collegamento già completato.")
            done = true
        }
        val code = params["code"]?.firstOrNull()
        return if (code != null) {
            onResult(Result.success(code))
            html(Response.Status.OK, "Collegamento completato. Puoi tornare alla TV.")
        } else {
            onResult(Result.failure(IllegalStateException(params["error"]?.firstOrNull() ?: "accesso negato")))
            html(Response.Status.OK, "Accesso non concesso. Riprova dalla TV.")
        }
    }

    private fun html(status: Response.Status, message: String): Response {
        val body = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<body style=\"font-family:sans-serif;text-align:center;margin-top:4rem\"><h2>$message</h2></body>"
        return newFixedLengthResponse(status, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }
}
