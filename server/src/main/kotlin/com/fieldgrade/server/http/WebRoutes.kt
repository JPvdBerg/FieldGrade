package com.fieldgrade.server.http

import com.fieldgrade.server.auth.AccountService
import com.fieldgrade.server.domain.User
import com.fieldgrade.server.machine.MachineService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

/**
 * The web UI: sign up, add a machine, get a pairing code.
 *
 * One job: render HTML and post forms. It calls the same services the JSON API
 * does, so there is no second implementation of any rule.
 *
 * Server-rendered, no JavaScript framework, no build step. This is an admin
 * surface a handful of farmers touch a few times a year — a single-page app
 * here would be more machinery to maintain than the thing it renders, and it
 * would still need the same endpoints underneath.
 *
 * **Payment deliberately does not live in the Android app.** Selling
 * subscriptions in-app drags in Play billing rules, and the 2026 relaxation
 * that permits your own processor covers the US, UK and EEA — not South
 * Africa. Signing up on the web sidesteps that entirely, whatever way the app
 * is eventually distributed.
 */
fun Route.webRoutes(accounts: AccountService, machines: MachineService) {

    get("/") {
        val user = call.sessionUser(accounts)
        if (user == null) call.respondRedirect("/login") else call.respondRedirect("/dashboard")
    }

    get("/health") { call.respondText("ok") }

    // ---------------------------------------------------------------- signup

    get("/signup") {
        call.respondPage("Create account", signupForm(null))
    }

    post("/signup") {
        val form = call.receiveParameters()
        val result = accounts.register(
            orgName = form["orgName"].orEmpty(),
            email = form["email"].orEmpty(),
            password = form["password"].orEmpty()
        )
        when (result) {
            is AccountService.RegisterResult.Success -> {
                call.setSessionCookie(result.sessionToken)
                call.respondRedirect("/dashboard")
            }
            AccountService.RegisterResult.EmailTaken ->
                call.respondPage(
                    "Create account",
                    signupForm("That email address is already registered."),
                    HttpStatusCode.Conflict
                )
            is AccountService.RegisterResult.Invalid ->
                call.respondPage(
                    "Create account", signupForm(result.reason), HttpStatusCode.BadRequest
                )
        }
    }

    // ---------------------------------------------------------------- login

    get("/login") {
        call.respondPage("Sign in", loginForm(null))
    }

    post("/login") {
        val form = call.receiveParameters()
        val token = accounts.login(form["email"].orEmpty(), form["password"].orEmpty())
        if (token == null) {
            call.respondPage(
                "Sign in", loginForm("Email or password is incorrect."), HttpStatusCode.Unauthorized
            )
        } else {
            call.setSessionCookie(token)
            call.respondRedirect("/dashboard")
        }
    }

    post("/logout") {
        accounts.logout(call.sessionCookie())
        call.clearSessionCookie()
        call.respondRedirect("/login")
    }

    // ---------------------------------------------------------------- dashboard

    get("/dashboard") {
        val user = call.sessionUser(accounts) ?: return@get call.respondRedirect("/login")
        call.respondPage("Machines", dashboard(user, machines.listMachines(user.orgId), null, null))
    }

    post("/machines") {
        val user = call.sessionUser(accounts) ?: return@post call.respondRedirect("/login")
        val form = call.receiveParameters()
        val result = machines.addMachine(
            user.orgId, form["serial"].orEmpty(), form["name"].orEmpty()
        )
        val error = when (result) {
            is MachineService.AddResult.Success -> null
            MachineService.AddResult.SerialTaken -> "That serial is already registered."
            is MachineService.AddResult.Invalid -> result.reason
        }
        call.respondPage("Machines", dashboard(user, machines.listMachines(user.orgId), null, error))
    }

    post("/machines/{id}/pair") {
        val user = call.sessionUser(accounts) ?: return@post call.respondRedirect("/login")
        val machine = machines.findOwned(user.orgId, call.parameters["id"].orEmpty())
            ?: return@post call.respondRedirect("/dashboard")
        val code = machines.createPairingCode(machine.id)
        call.respondPage(
            "Pair ${machine.name}",
            dashboard(user, machines.listMachines(user.orgId), machine.name to code, null)
        )
    }
}

// ---------------------------------------------------------------- session cookie

private const val SESSION_COOKIE = "fg_session"

private fun ApplicationCall.sessionCookie(): String? = request.cookies[SESSION_COOKIE]

private suspend fun ApplicationCall.sessionUser(accounts: AccountService): User? =
    accounts.authenticate(sessionCookie())

private fun ApplicationCall.setSessionCookie(token: String) {
    // HttpOnly so no script can read it; SameSite=Lax so a form post from
    // another site cannot ride the session. Secure is added by the reverse
    // proxy's TLS in production — behind Cloudflare Tunnel that is always on.
    response.headers.append(
        "Set-Cookie",
        "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=${60 * 60 * 24 * 60}"
    )
}

private fun ApplicationCall.clearSessionCookie() {
    response.headers.append("Set-Cookie", "$SESSION_COOKIE=; Path=/; HttpOnly; Max-Age=0")
}

// ---------------------------------------------------------------- rendering

private suspend fun ApplicationCall.respondPage(
    title: String, body: String, status: HttpStatusCode = HttpStatusCode.OK
) {
    respondText(page(title, body), ContentType.Text.Html, status)
}

/**
 * Every value interpolated into HTML goes through [esc].
 *
 * Machine names and organisation names are user input and end up on a page an
 * owner shows to someone else. Escaping at the point of interpolation, with no
 * exceptions, is the only version of this rule that survives contact with a
 * codebase.
 */
private fun esc(value: String?): String = (value ?: "")
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

private fun page(title: String, body: String): String = """
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)} — FieldGrade</title>
<style>
  :root { color-scheme: dark; --bg:#14181c; --panel:#1b2026; --line:#2a3038;
          --ink:#e6e9ec; --muted:#9aa0a6; --accent:#3987e5; --warn:#ef6c00; --ok:#4caf50; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--ink);
         font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif; }
  header { border-bottom:1px solid var(--line); padding:14px 20px;
           display:flex; align-items:center; gap:16px; }
  header b { font-size:16px; letter-spacing:.02em; }
  main { max-width:820px; margin:0 auto; padding:28px 20px 60px; }
  h1 { font-size:22px; margin:0 0 4px; }
  p.sub { color:var(--muted); margin:0 0 24px; }
  .card { background:var(--panel); border:1px solid var(--line);
          border-radius:10px; padding:18px; margin-bottom:18px; }
  label { display:block; font-size:13px; color:var(--muted); margin:12px 0 4px; }
  input { width:100%; padding:10px 12px; border-radius:7px;
          border:1px solid var(--line); background:#11151a; color:var(--ink); font-size:15px; }
  button { margin-top:16px; padding:10px 18px; border:0; border-radius:7px;
           background:var(--accent); color:#fff; font-weight:600; font-size:15px; cursor:pointer; }
  button.ghost { background:transparent; border:1px solid var(--line); color:var(--ink); }
  table { width:100%; border-collapse:collapse; }
  th,td { text-align:left; padding:10px 8px; border-bottom:1px solid var(--line); font-size:14px; }
  th { color:var(--muted); font-weight:500; font-size:12px; text-transform:uppercase; }
  .err { background:#4a1d00; border:1px solid var(--warn); color:#ffd7ae;
         padding:10px 12px; border-radius:7px; margin-bottom:16px; font-size:14px; }
  .code { font:28px/1.2 ui-monospace,SFMono-Regular,Menlo,monospace;
          letter-spacing:.12em; padding:14px 0; color:var(--ok); }
  .pill { font-size:12px; padding:2px 8px; border-radius:99px; border:1px solid var(--line); }
  a { color:var(--accent); }
  .row { display:flex; gap:12px; align-items:flex-end; flex-wrap:wrap; }
  .row > div { flex:1 1 180px; }
</style>
</head><body>
<header><b>FieldGrade</b><span style="color:var(--muted)">control plane</span></header>
<main>$body</main>
</body></html>
""".trimIndent()

private fun signupForm(error: String?) = """
<h1>Create account</h1>
<p class="sub">One account per business. You can add machines and operators afterwards.</p>
${errorBlock(error)}
<div class="card"><form method="post" action="/signup">
  <label for="orgName">Business name</label>
  <input id="orgName" name="orgName" required autocomplete="organization">
  <label for="email">Email</label>
  <input id="email" name="email" type="email" required autocomplete="email">
  <label for="password">Password</label>
  <input id="password" name="password" type="password" required minlength="10"
         autocomplete="new-password">
  <button type="submit">Create account</button>
</form></div>
<p class="sub">Already have one? <a href="/login">Sign in</a>.</p>
""".trimIndent()

private fun loginForm(error: String?) = """
<h1>Sign in</h1>
${errorBlock(error)}
<div class="card"><form method="post" action="/login">
  <label for="email">Email</label>
  <input id="email" name="email" type="email" required autocomplete="email">
  <label for="password">Password</label>
  <input id="password" name="password" type="password" required autocomplete="current-password">
  <button type="submit">Sign in</button>
</form></div>
<p class="sub">No account yet? <a href="/signup">Create one</a>.</p>
""".trimIndent()

private fun errorBlock(error: String?) =
    if (error == null) "" else """<div class="err">${esc(error)}</div>"""

private val DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC)

private fun dashboard(
    user: User,
    machines: List<MachineService.MachineSummary>,
    pairing: Pair<String, String>?,
    error: String?
): String {
    val pairingBlock = pairing?.let { (name, code) ->
        """
        <div class="card">
          <h1>Pair ${esc(name)}</h1>
          <p class="sub">On the tablet: <b>Settings &rarr; Pair machine</b>, then type this code.
             It is valid for 15 minutes and can only be used once.</p>
          <div class="code">${esc(code)}</div>
          <p class="sub">The tablet needs signal for this one step only. After pairing it works
             offline indefinitely.</p>
        </div>
        """.trimIndent()
    }.orEmpty()

    val rows = if (machines.isEmpty()) {
        """<tr><td colspan="4" style="color:var(--muted)">No machines yet.</td></tr>"""
    } else {
        machines.joinToString("\n") { summary ->
            val sub = summary.subscription
            val status = sub?.status?.wire ?: "none"
            val paid = sub?.let { DATE.format(it.currentPeriodEnd) } ?: "—"
            """
            <tr>
              <td><b>${esc(summary.machine.name)}</b></td>
              <td style="color:var(--muted)">${esc(summary.machine.serial)}</td>
              <td><span class="pill">${esc(status)}</span> <span
                  style="color:var(--muted)">to ${esc(paid)}</span></td>
              <td><form method="post" action="/machines/${esc(summary.machine.id)}/pair"
                   style="margin:0"><button class="ghost" type="submit"
                   style="margin:0;padding:6px 12px">Pair</button></form></td>
            </tr>
            """.trimIndent()
        }
    }

    return """
$pairingBlock
<h1>Machines</h1>
<p class="sub">Signed in as ${esc(user.email)} &middot;
   <form method="post" action="/logout" style="display:inline">
     <button class="ghost" type="submit"
             style="margin:0;padding:2px 10px;font-size:13px">Sign out</button>
   </form></p>
${errorBlock(error)}
<div class="card">
  <table>
    <tr><th>Name</th><th>Serial</th><th>Subscription</th><th></th></tr>
    $rows
  </table>
</div>
<div class="card">
  <h1 style="font-size:16px">Add a machine</h1>
  <p class="sub">The serial is stamped on the controller. Every machine gets a 30-day trial.</p>
  <form method="post" action="/machines"><div class="row">
    <div><label for="serial">Serial</label>
         <input id="serial" name="serial" required placeholder="SN-0001"></div>
    <div><label for="name">Name</label>
         <input id="name" name="name" required placeholder="Scraper 1"></div>
    <div style="flex:0 0 auto"><button type="submit">Add</button></div>
  </div></form>
</div>
    """.trimIndent()
}
