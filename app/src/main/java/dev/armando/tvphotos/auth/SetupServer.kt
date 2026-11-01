package dev.armando.tvphotos.auth

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

/**
 * Server HTTP temporaneo in LAN con un modulo per inserire dal telefono le credenziali Google.
 * Il percorso contiene un token casuale, mostrato solo nel QR code sulla TV.
 */
class SetupServer(
    port: Int,
    private val token: String,
    private val defaultPageUrl: String,
    /** Riceve i valori inviati; restituisce un messaggio d'errore oppure null se sono stati accettati. */
    private val onSubmit: (clientId: String, clientSecret: String, pageUrl: String) -> String?,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/setup/$token") return page(Response.Status.NOT_FOUND, "<h2>Pagina non trovata</h2>")
        return when (session.method) {
            Method.GET -> page(Response.Status.OK, form(null))
            Method.POST -> {
                runCatching { session.parseBody(HashMap()) }
                val p = session.parameters
                val error = onSubmit(
                    p["client_id"]?.firstOrNull().orEmpty(),
                    p["client_secret"]?.firstOrNull().orEmpty(),
                    p["page_url"]?.firstOrNull().orEmpty(),
                )
                if (error == null) {
                    page(Response.Status.OK, "<h2>Credenziali salvate</h2><p>Torna alla TV: ti verrà chiesto di collegare l'account Google.</p>")
                } else {
                    page(Response.Status.OK, form(error))
                }
            }
            else -> page(Response.Status.METHOD_NOT_ALLOWED, "<h2>Metodo non consentito</h2>")
        }
    }

    private fun form(error: String?): String = buildString {
        append("<h2>Credenziali Google</h2>")
        append("<p>Incolla i dati del client OAuth <b>Applicazione web</b> del tuo progetto Google Cloud.</p>")
        append("<p>URI di reindirizzamento da registrare nel client:<br><code>").append(esc(defaultPageUrl)).append("</code></p>")
        if (error != null) append("<p class=\"err\">").append(esc(error)).append("</p>")
        append("<form method=\"post\" autocomplete=\"off\">")
        append("<label>ID client<input name=\"client_id\" required autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"></label>")
        append("<label>Client secret<input name=\"client_secret\" type=\"password\" required autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"></label>")
        append("<label>Pagina di reindirizzamento (facoltativa)<input name=\"page_url\" placeholder=\"").append(esc(defaultPageUrl)).append("\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"></label>")
        append("<button type=\"submit\">Salva sulla TV</button></form>")
        append("<p class=\"note\">I dati viaggiano solo sulla tua rete locale, verso la TV.</p>")
    }

    private fun page(status: Response.Status, body: String): Response {
        val html = "<!doctype html><html lang=\"it\"><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Foto TV</title>" +
            "<style>body{font-family:system-ui,sans-serif;margin:0;padding:1.5rem;max-width:32rem;margin:auto;line-height:1.4}" +
            "label{display:block;margin:1rem 0 0;font-weight:600}input{display:block;width:100%;box-sizing:border-box;padding:.7rem;margin-top:.3rem;font-size:1rem}" +
            "button{margin-top:1.5rem;padding:.9rem 1.2rem;font-size:1rem;width:100%}code{word-break:break-all}.err{color:#b00020;font-weight:600}.note{color:#555;font-size:.85rem}</style>" +
            "<body>$body</body></html>"
        return newFixedLengthResponse(status, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
