package sportground;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Sportground web app. No dependencies, uses the server built into Java.
// run it with: java -cp out sportground.App
public class App {
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
    // wrong-password tracker: email -> count, locks, locked-until time
    static final Map<String, Integer> loginFails = new ConcurrentHashMap<>();
    static final Map<String, Integer> loginLocks = new ConcurrentHashMap<>();
    static final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    static final Map<String, List<String>> COURTS = Map.of(
        "basketball", List.of("A", "B"),
        "volleyball", List.of("A", "B"),
        "badminton", List.of("A", "B"),
        "billiard", List.of("A", "B", "C"));

    public static void main(String[] args) throws Exception {
        Db.init();
        HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
        s.createContext("/static/", App::serveStatic);
        s.createContext("/", App::route);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.start();
        System.out.println("Sportground running at http://localhost:" + PORT);
    }

// infra
    static void serveStatic(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath().replace("/static/", "");
            if (p.isEmpty() || p.contains("..")) { send(ex, 404, "Not found", "text/plain"); return; }
            Path f = Paths.get("public", p);
            if (!Files.exists(f) || Files.isDirectory(f)) { send(ex, 404, "Not found", "text/plain"); return; }
            String ct = "application/octet-stream";
            if (p.endsWith(".css")) ct = "text/css";
            else if (p.endsWith(".svg")) ct = "image/svg+xml";
            else if (p.endsWith(".js")) ct = "text/javascript";
            else if (p.endsWith(".html")) ct = "text/html; charset=UTF-8";
            else if (p.endsWith(".png")) ct = "image/png";
            sendBytes(ex, 200, Files.readAllBytes(f), ct);
        } catch (Exception e) { send(ex, 500, "Static error: " + e, "text/plain"); }
    }

    static void route(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            Map<String, String> q = parseQuery(uri.getRawQuery());
            Map<String, String> form = method.equals("POST") ? parseForm(ex) : Map.of();
            Db.User user = currentUser(ex);

            if (path.equals("/") && method.equals("GET")) returnHtml(ex, Html.layout("Home", Html.landing(Db.loadRates()), user));
            else if (path.equals("/login") && method.equals("GET")) returnHtml(ex, Html.layout("Login", Html.loginForm(null, "", 0), user));
            else if (path.equals("/login") && method.equals("POST")) doLogin(ex, form);
            else if (path.equals("/register") && method.equals("GET")) returnHtml(ex, Html.layout("Register", Html.registerForm(null), user));
            else if (path.equals("/register") && method.equals("POST")) doRegister(ex, form);
            else if (path.equals("/logout")) doLogout(ex);
            else if (path.equals("/dashboard") && method.equals("GET")) { if (!needLogin(ex, user)) return; if (user.role.equals("admin")) { redirect(ex, "/admin"); return; } returnHtml(ex, Html.layout("Dashboard", dashboard(user, q), user)); }
            else if (path.equals("/book") && method.equals("GET")) { if (!needLogin(ex, user)) return; if (user.role.equals("admin")) { returnHtml(ex, Html.layout("Book", "<h2>Booking</h2><p class='err'>Admins cannot book. Booking is for customers only.</p><p><a href='/admin'>Back to Admin</a></p>", user)); return; } returnHtml(ex, Html.layout("Book", bookForm(user, q, null), user)); }
            else if (path.equals("/book") && method.equals("POST")) { if (!needLogin(ex, user)) return; if (user.role.equals("admin")) { send(ex, 403, "Admins cannot book.", "text/html"); return; } doBook(ex, user, form); }
            else if (path.equals("/my-bookings")) { if (!needLogin(ex, user)) return; if (user.role.equals("admin")) { redirect(ex, "/admin"); return; } returnHtml(ex, Html.layout("My Bookings", myBookings(user, q.getOrDefault("msg", null)), user)); }
            else if (path.equals("/edit") && method.equals("GET")) { if (!needLogin(ex, user)) return; editGet(ex, user, q); }
            else if (path.equals("/edit") && method.equals("POST")) { if (!needLogin(ex, user)) return; editPost(ex, user, form); }
            else if (path.equals("/cancel") && method.equals("POST")) { if (!needLogin(ex, user)) return; doCancel(ex, user, form, false); }
            else if (path.equals("/takeback") && method.equals("POST")) { if (!needLogin(ex, user)) return; doTakeBack(ex, user, form); }
            else if (path.equals("/delete") && method.equals("POST")) { if (!needLogin(ex, user)) return; doDelete(ex, user, form); }
            else if (path.equals("/wallet")) { if (!needLogin(ex, user)) return; returnHtml(ex, Html.layout("Wallet", wallet(user), user)); }
            else if (path.equals("/profile") && method.equals("GET")) { if (!needLogin(ex, user)) return; if (user.role.equals("admin")) { redirect(ex, "/admin"); return; } returnHtml(ex, Html.layout("Profile", profileForm(Db.findById(user.id), null, null), user)); }
            else if (path.equals("/profile") && method.equals("POST")) { if (!needLogin(ex, user)) return; if (user.role.equals("admin")) { redirect(ex, "/admin"); return; } doProfile(ex, user, form); }
            else if (path.equals("/admin") && method.equals("GET")) { if (!needAdmin(ex, user)) return; returnHtml(ex, Html.layout("Admin", adminPage(q), user)); }
            else if (path.equals("/admin/cancel") && method.equals("POST")) { if (!needAdmin(ex, user)) return; doCancel(ex, user, form, true); }
            else if (path.equals("/admin/keep") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminKeep(ex, form); }
            else if (path.equals("/admin/topup") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminTopup(ex, form); }
            else if (path.equals("/admin/password") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminPassword(ex, form); }
            else if (path.equals("/admin/rate") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminRate(ex, form); }
            else if (path.equals("/admin/delete") && method.equals("POST")) { if (!needAdmin(ex, user)) return; adminHardDelete(ex, form); }
            else send(ex, 404, "Not found. <a href='/'>Home</a>", "text/html");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "Server error: " + Html.esc(String.valueOf(e.getMessage())), "text/html");
        }
    }

// auth routes
    static void doLogin(HttpExchange ex, Map<String, String> f) throws IOException {
        String email = f.getOrDefault("email", "").trim().toLowerCase();
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(email);
        if (until != null && now < until) {
            long secs = (until - now + 999) / 1000;
            returnHtml(ex, Html.layout("Login", Html.loginForm(null, email, secs), null)); return;
        }
        if (!email.endsWith("@sportground.ph")) {
            returnHtml(ex, Html.layout("Login", Html.loginForm("Email must end with @sportground.ph.", email, 0), null)); return;
        }
        Db.User u = Db.findByEmail(email);
        if (u == null) {
            returnHtml(ex, Html.layout("Login", Html.loginForm("No account with this email. Please register first.", email, 0), null)); return;
        }
        if (!Auth.verify(f.getOrDefault("password", ""), u.salt, u.hash)) {
            int n = loginFails.getOrDefault(email, 0) + 1;
            if (n >= 5) {
                int locks = loginLocks.getOrDefault(email, 0) + 1;
                loginLocks.put(email, locks);
                loginFails.put(email, 0);
                long secs = 30L * locks;
                lockedUntil.put(email, now + secs * 1000);
                returnHtml(ex, Html.layout("Login", Html.loginForm(null, email, secs), null)); return;
            }
            loginFails.put(email, n);
            returnHtml(ex, Html.layout("Login", Html.loginForm("Wrong email or password. (" + n + " of 5 tries)", email, 0), null)); return;
        }
        loginFails.remove(email); lockedUntil.remove(email);
        String sid = Auth.createSession(u.id);
        ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=" + sid + "; Path=/; HttpOnly");
        redirect(ex, u.role.equals("admin") ? "/admin" : "/dashboard");
    }

    static void doRegister(HttpExchange ex, Map<String, String> f) throws IOException {
        String fn = f.getOrDefault("firstname", "").trim().replaceAll("\\s+", " ");
        String ln = f.getOrDefault("lastname", "").trim().replaceAll("\\s+", " ");
        String name = (fn + " " + ln).trim();
        String email = f.getOrDefault("email", "").trim().toLowerCase(), pw = f.getOrDefault("password", "");
        String err = null;
        if (fn.isEmpty() || ln.isEmpty()) err = "Enter your first name and last name.";
        else if (!email.endsWith("@sportground.ph")) err = "Email must end with @sportground.ph.";
        else if (pw.length() < 6) err = "Password must be at least 6 characters.";
        else if (email.contains("|")) err = "Invalid email.";
        else {
            for (Db.User x : Db.loadUsers()) if (x.name.equalsIgnoreCase(name)) { err = "That name is already registered."; break; }
            if (err == null && Db.findByEmail(email) != null) err = "Email already registered. Please login.";
        }
        if (err != null) { returnHtml(ex, Html.layout("Register", Html.registerForm(err), null)); return; }
        String salt = Auth.genSalt();
        List<Db.User> users = Db.loadUsers();
        Db.User nu = new Db.User(Db.nextId("U"), name, email, Auth.hash(pw, salt), salt, "customer", 0, pw.length());
        users.add(nu); Db.saveUsers(users);
        Db.addTxn(nu.id, "info", 0, "Account created");
        String sid = Auth.createSession(nu.id);
        ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=" + sid + "; Path=/; HttpOnly");
        redirect(ex, "/dashboard");
    }

    static void doLogout(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "SESSIONID");
        Auth.destroy(sid);
        ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=; Path=/; Max-Age=0");
        redirect(ex, "/");
    }

    static Db.User currentUser(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "SESSIONID");
        String uid = Auth.getUserId(sid);
        return uid == null ? null : Db.findById(uid);
    }

    static boolean needLogin(HttpExchange ex, Db.User u) throws IOException {
        if (u == null) { redirect(ex, "/login"); return false; } return true;
    }
    static boolean needAdmin(HttpExchange ex, Db.User u) throws IOException {
        if (u == null) { redirect(ex, "/login"); return false; }
        if (!u.role.equals("admin")) { send(ex, 403, "Admins only.", "text/html"); return false; }
        return true;
    }

// booking rules
    static int toMin(String t) { String[] p = t.split(":"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); }
    static boolean overlap(String s1, String e1, String s2, String e2) { return toMin(s1) < toMin(e2) && toMin(e2) > toMin(s2); }

    // returns an error message, or null when the booking is OK
    // id is passed so edit can skip the row it is editing
    static String validateBooking(Db.User user, String sport, String court, String date, String start, String end, String excludeId) throws IOException {
        sport = sport.toLowerCase();
        if (!COURTS.containsKey(sport)) return "Invalid sport.";
        if (!COURTS.get(sport).contains(court)) return "Invalid court for " + sport + ". Valid: " + COURTS.get(sport);
        LocalDate d;
        try { d = LocalDate.parse(date); } catch (Exception e) { return "Invalid date (use YYYY-MM-DD)."; }
        if (d.isBefore(LocalDate.now())) return "Date cannot be in the past.";
        int s, e;
        try { s = toMin(start); e = toMin(end); } catch (Exception ex) { return "Invalid time (use HH:MM, 24h)."; }
        if (s >= e) return "Start must be before end.";
        if (s < 8 * 60) return "No booking before 8:00 AM.";
        if (e > 24 * 60) return "No booking after 12:00 AM (midnight).";
        if (s % 60 != 0 || e % 60 != 0) return "Pick whole hours only (example 9:00 to 11:00).";
        double hrs = (e - s) / 60.0;
        if (hrs < 1) return "Minimum booking is 1 hour.";
        // no max hours, as long as it fits 8AM to midnight
        List<Db.Reservation> all = Db.loadReservations();
        for (Db.Reservation r : all) {
            if (r.id.equals(excludeId)) continue;
            if (!r.status.equals("confirmed") && !r.status.equals("pending")) continue;
            if (r.sport.equals(sport) && r.court.equals(court) && r.date.equals(date) && overlap(start, end, r.start, r.end))
                return "Slot taken: " + Html.cap(sport) + " Court " + court + " already booked " + r.start + "–" + r.end + " on " + date + ". Pick another time/court.";
        }
        if (!user.role.equals("admin")) {
            for (Db.Reservation r : all) {
                if (r.id.equals(excludeId)) continue;
                if (!r.status.equals("confirmed") && !r.status.equals("pending")) continue;
                if (r.userId.equals(user.id) && r.sport.equals(sport) && r.date.equals(date))
                    return "Rule: only 1 reservation per sport per day. You already have " + Html.cap(sport) + " on " + date + ".";
            }
        }
        return null;
    }

    // minutes to HH:MM, 1440 becomes 24:00 (midnight)
    static String fmtTime(int mins) { return String.format("%02d:%02d", mins / 60, mins % 60); }

// booking: create, cancel request, take back
    static void doBook(HttpExchange ex, Db.User user, Map<String, String> f) throws IOException {
        String sport = f.getOrDefault("sport", "").toLowerCase(), court = f.getOrDefault("court", "A").toUpperCase();
        String date = f.getOrDefault("date", ""), start = f.getOrDefault("start", "");
        int dur = 0;
        try { dur = Integer.parseInt(f.getOrDefault("hours", "0")); } catch (Exception ignored) {}
        String end = "";
        try { end = fmtTime(toMin(start) + dur * 60); } catch (Exception ignored) {}
        String err = dur < 1 ? "Pick how many hours (at least 1)." : validateBooking(user, sport, court, date, start, end, null);
        Map<String, Integer> rates = Db.loadRates();
        double hrs = 0; int price = 0;
        try { hrs = (toMin(end) - toMin(start)) / 60.0; price = (int) Math.round(rates.get(sport) * hrs); } catch (Exception ignored) {}
        if (err == null && user.role.equals("customer")) {
            Db.User fresh = Db.findById(user.id);
            if (fresh.balance < price) err = "Insufficient balance: need ₱" + price + " but you have ₱" + fresh.balance + ". Ask admin to top-up your wallet.";
        }
        if (err != null) { returnHtml(ex, Html.layout("Book", bookForm(user, toMap(f), err), user)); return; }
        // take the money out and save
        List<Db.User> users = Db.loadUsers();
        for (Db.User u : users) if (u.id.equals(user.id) && u.role.equals("customer")) u.balance -= price;
        Db.saveUsers(users);
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation r = new Db.Reservation(Db.nextId("R"), user.id, sport, court, date, start, end, hrs, price, "confirmed", LocalDateTime.now().toString());
        all.add(r); Db.saveReservations(all);
        if (user.role.equals("customer")) Db.addTxn(user.id, "pay", -price, "Booking " + r.id + " " + sport + " " + court + " " + date + " " + start + "-" + end);
        redirect(ex, "/my-bookings");
    }

    static void doCancel(HttpExchange ex, Db.User actor, Map<String, String> f, boolean isAdmin) throws IOException {
        String id = f.getOrDefault("id", "");
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation target = null;
        for (Db.Reservation r : all) if (r.id.equals(id)) target = r;
        if (target == null) { send(ex, 404, "Booking not found", "text/html"); return; }
        if (!isAdmin && !target.userId.equals(actor.id)) { send(ex, 403, "Not yours.", "text/html"); return; }
        if (target.status.equals("cancelled")) { redirect(ex, isAdmin ? "/admin" : "/my-bookings"); return; }
        if (!isAdmin) {
            // customer cancel is only a request, admin confirms it later
            if (target.status.equals("pending")) { redirect(ex, "/my-bookings"); return; }
            target.status = "pending";
            Db.saveReservations(all);
            Db.addTxn(target.userId, "info", 0, "Cancel requested for " + target.id + " (waits for admin)");
            redirect(ex, "/my-bookings?msg=" + URLEncoder.encode("Cancel requested. The refund comes after the admin confirms.", "UTF-8"));
            return;
        }
        boolean wasPending = target.status.equals("pending");
        target.status = "cancelled";
        Db.saveReservations(all);
        // give the refund to the one who paid
        List<Db.User> users = Db.loadUsers();
        for (Db.User u : users) if (u.id.equals(target.userId)) u.balance += target.price;
        Db.saveUsers(users);
        Db.addTxn(target.userId, "refund", target.price, "Refund booking " + target.id + (wasPending ? " (cancel confirmed)" : " (admin cancelled)"));
        redirect(ex, "/admin");
    }

    // customer takes back a cancel request
    static void doTakeBack(HttpExchange ex, Db.User actor, Map<String, String> f) throws IOException {
        String id = f.getOrDefault("id", "");
        List<Db.Reservation> all = Db.loadReservations();
        for (Db.Reservation r : all) {
            if (r.id.equals(id) && r.userId.equals(actor.id) && r.status.equals("pending")) r.status = "confirmed";
        }
        Db.saveReservations(all);
        redirect(ex, "/my-bookings");
    }

    static void doDelete(HttpExchange ex, Db.User actor, Map<String, String> f) throws IOException {
        String id = f.getOrDefault("id", "");
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation target = null;
        for (Db.Reservation r : all) if (r.id.equals(id)) target = r;
        if (target == null) { send(ex, 404, "Not found", "text/html"); return; }
        boolean admin = actor.role.equals("admin");
        if (!admin && !target.userId.equals(actor.id)) { send(ex, 403, "Not yours.", "text/html"); return; }
        if (!admin && !target.status.equals("cancelled")) { send(ex, 400, "Cancel first before deleting. <a href='/my-bookings'>Back</a>", "text/html"); return; }
        final String fid = id;
        all.removeIf(r -> r.id.equals(fid));
        Db.saveReservations(all);
        redirect(ex, admin ? "/admin" : "/my-bookings");
    }

// customer pages: dashboard, booking form, my bookings, edit
    static String dashboard(Db.User u, Map<String, String> q) throws IOException {
        Map<String, Integer> rates = Db.loadRates();
        List<Db.Reservation> all = Db.loadReservations();
        List<Db.Reservation> mine = new ArrayList<>();
        for (Db.Reservation r : all) if (r.userId.equals(u.id) && !r.status.equals("cancelled")) mine.add(r);
        mine.sort(Comparator.comparing((Db.Reservation r) -> r.date).thenComparing(r -> r.start));
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Dashboard</h2><p>Balance: <b>₱").append(u.balance).append("</b> • Hours: 8AM–12AM • <a href='/wallet'>Wallet history</a></p>");
        sb.append("<div class='grid'>");
        for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
            sb.append("<div class='card sm'><h3 style='text-transform:capitalize'>").append(s).append("</h3><p class='price'>₱").append(rates.get(s)).append("/hr</p><a class='btn' href='/book?sport=").append(s).append("'>Book</a></div>");
        sb.append("</div>");
        sb.append("<h3>Check availability</h3><form class='form inline' method='GET' action='/dashboard'>"
            + sportSelect(q.getOrDefault("sport", "basketball")) + courtSelect(q.getOrDefault("court", "A"))
            + "<label>Date<input type='date' name='date' value='" + Html.esc(q.getOrDefault("date", LocalDate.now().toString())) + "'></label>"
            + "<button class='btn' type='submit'>Check</button></form>");
        if (q.containsKey("date")) sb.append(availabilityTable(q.getOrDefault("sport", "basketball"), q.getOrDefault("court", "A"), q.getOrDefault("date", LocalDate.now().toString())));
        sb.append("<h3>Your upcoming (").append(mine.size()).append(")</h3>");
        if (mine.isEmpty()) sb.append("<p class='muted'>No bookings yet. <a href='/book'>Book now</a></p>");
        else { sb.append("<div class='tablewrap'><table><tr><th>Sport</th><th>Court</th><th>Date</th><th>Time</th><th>Price</th><th>Status</th></tr>");
            for (Db.Reservation r : mine.subList(0, Math.min(5, mine.size())))
                sb.append("<tr><td>").append(Html.cap(r.sport)).append("</td><td>").append(r.court).append("</td><td>").append(r.date).append("</td><td>").append(r.start).append("–").append(r.end).append("</td><td>₱").append(r.price).append("</td><td>").append(Html.cap(r.status)).append("</td></tr>");
            sb.append("</table></div><p><a href='/my-bookings'>View all / edit →</a></p>");
        }
        return sb.toString();
    }

    static String availabilityTable(String sport, String court, String date) throws IOException {
        sport = sport.toLowerCase();
        StringBuilder sb = new StringBuilder("<div class='tablewrap'><table><caption>Booked: " + Html.cap(sport) + " Court " + Html.esc(court) + " on " + Html.esc(date) + "</caption><tr><th>Time</th><th>Status</th></tr>");
        boolean any = false;
        for (Db.Reservation r : Db.loadReservations())
            if (r.sport.equals(sport) && r.court.equals(court) && r.date.equals(date) && (r.status.equals("confirmed") || r.status.equals("pending"))) {
                sb.append("<tr><td>").append(r.start).append("–").append(r.end).append("</td><td>⛔ Taken</td></tr>"); any = true;
            }
        if (!any) sb.append("<tr><td colspan='2'>✅ Whole day free 8AM–12AM</td></tr>");
        return sb.append("</table></div>").toString();
    }

    static String sportSelect(String sel) {
        StringBuilder sb = new StringBuilder("<label>Sport<select name='sport'>");
        for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
            sb.append("<option value='").append(s).append("'").append(s.equals(sel) ? " selected" : "").append(">").append(Html.cap(s)).append("</option>");
        return sb.append("</select></label>").toString();
    }
    // whole-hour dropdowns: start 8AM-11PM, end 9AM-midnight
    // 24 to 12 hour: 8 -> 8:00 AM, 13 -> 1:00 PM, 23 -> 11:00 PM
    static String h12(int h) {
        int m = h % 24;
        String ap = m < 12 ? "AM" : "PM";
        int x = m % 12; if (x == 0) x = 12;
        return x + ":00 " + ap;
    }
    static String hourStartSelect(String sel) {
        StringBuilder sb = new StringBuilder("<label>Start<select name='start'>");
        for (int h = 8; h <= 23; h++) {
            String t = String.format("%02d:00", h);
            sb.append("<option value='").append(t).append("'").append(t.equals(sel) ? " selected" : "").append(">").append(h12(h)).append("</option>");
        }
        return sb.append("</select></label>").toString();
    }
    static String courtSelect(String sel) {
        StringBuilder sb = new StringBuilder("<label>Court<select name='court'>");
        for (String c : List.of("A", "B", "C"))
            sb.append("<option").append(c.equals(sel) ? " selected" : "").append(">").append(c).append("</option>");
        return sb.append("</select></label>").toString();
    }

    // how-many-hours dropdown, choices stop where midnight starts
    static String durationSelect(String sel, int startHour) {
        StringBuilder sb = new StringBuilder("<label>Hours<select name='hours'>");
        for (int h = 1; h <= 24 - startHour; h++) {
            sb.append("<option value='").append(h).append("'").append(String.valueOf(h).equals(sel) ? " selected" : "").append(">").append(h).append(h == 1 ? " hour" : " hours").append("</option>");
        }
        return sb.append("</select></label>").toString();
    }

    static String bookForm(Db.User u, Map<String, String> v, String err) throws IOException {
        Map<String, Integer> rates = Db.loadRates();
        String sport = v.getOrDefault("sport", "basketball").toLowerCase();
        String court = v.getOrDefault("court", "A"), date = v.getOrDefault("date", LocalDate.now().toString());
        String start = v.getOrDefault("start", "09:00"), hours = v.getOrDefault("hours", "1");
        int startHour = 9;
        try { startHour = Integer.parseInt(start.split(":")[0]); } catch (Exception ignored) {}
        StringBuilder sb = new StringBuilder("<h2>New Reservation</h2>");
        if (err != null) sb.append("<p class='err'>").append(Html.esc(err)).append("</p>");
        sb.append("<p>Balance: <b>₱").append(u.balance).append("</b> • Open 8:00 AM–12:00 AM • Whole hours only</p>");
        sb.append("<form class='form' method='POST' action='/book' onsubmit='return confirmDel(\"Confirm booking? ₱\"+document.getElementById(\"est\").textContent+\" will be deducted.\")'>");
        sb.append(sportSelect(sport));
        sb.append("<label>Court/Table<select name='court' id='court'></select></label>");
        sb.append("<img id='preview' src='/static/images/basketball-a.jpg' alt='court preview' style='width:100%;border-radius:10px'>");
        sb.append("<label>Date<input type='date' name='date' value='").append(Html.esc(date)).append("' min='").append(LocalDate.now()).append("' required></label>");
        sb.append(hourStartSelect(start));
        sb.append("<span id='durwrap'>").append(durationSelect(hours, startHour)).append("</span>");
        sb.append("<p>Ends at: <b id='endat'>-</b> • Estimated: ₱<b id='est'>0</b></p><button class='btn big' type='submit'>Confirm Booking</button></form>");
        sb.append("<script>const rates={");
        rates.forEach((k, val) -> sb.append("'").append(k).append("':").append(val).append(","));
        sb.append("};const courts={basketball:['A','B'],volleyball:['A','B'],badminton:['A','B'],billiard:['A','B','C']};");
        sb.append("const photos={basketball:{A:'/static/images/basketball-a.jpg',B:'/static/images/basketball-b.jpg'},"
            + "volleyball:{A:'/static/images/volleyball-a.jpg',B:'/static/images/volleyball-b.jpg'},"
            + "badminton:{A:'/static/images/badminton-a.jpg',B:'/static/images/badminton-b.jpg'},"
            + "billiard:{A:'/static/images/billiard.jpg',B:'/static/images/billiard.jpg',C:'/static/images/billiard.jpg'}};");
        sb.append("const svg={basketball:'/static/images/basketball.svg',volleyball:'/static/images/volleyball.svg',badminton:'/static/images/badminton.svg',billiard:'/static/images/billiard.svg'};");
        sb.append("const ss=document.querySelector(\"select[name='sport']\"),cs=document.getElementById('court');"
            + "function showPic(){const img=document.getElementById('preview');const p=(photos[ss.value]||{})[cs.value]||svg[ss.value];img.onerror=function(){img.onerror=null;img.src=svg[ss.value];};img.src=p;img.alt=ss.value+' court '+cs.value;}"
            + "function fillC(){cs.innerHTML='';courts[ss.value].forEach(c=>{const o=document.createElement('option');o.textContent=c;if(c==='" + court.replace("'", "") + "')o.selected=true;cs.appendChild(o);});showPic();calc();}"
            + "function fillH(){const st=parseInt(document.querySelector(\"select[name='start']\").value.split(':')[0],10);"
            + "const ds=document.querySelector(\"select[name='hours']\");const cur=ds.value;ds.innerHTML='';"
            + "for(let h=1;h<=24-st;h++){const o=document.createElement('option');o.value=h;o.textContent=h+(h==1?' hour':' hours');ds.appendChild(o);}"
            + "if(cur<=24-st)ds.value=cur;calc();}"
            + "function fmt12(h){h=h%24;const ap=h<12?'AM':'PM';let x=h%12;if(x==0)x=12;return x+':00 '+ap;}"
            + "function calc(){const st=parseInt(document.querySelector(\"select[name='start']\").value.split(':')[0],10);const h=parseInt(document.querySelector(\"select[name='hours']\").value,10)||0;"
            + "document.getElementById('est').textContent=h>0?h*rates[ss.value]:0;"
            + "document.getElementById('endat').textContent=h>0?fmt12(st+h):'-';}"
            + "ss.onchange=fillC;cs.onchange=showPic;document.querySelector(\"select[name='start']\").onchange=fillH;document.querySelector(\"select[name='hours']\").onchange=calc;fillC();</script>");
        sb.append(availabilityTable(sport, court, date));
        return sb.toString();
    }

    static String myBookings(Db.User u, String msg) throws IOException {
        List<Db.Reservation> all = Db.loadReservations();
        List<Db.Reservation> mine = new ArrayList<>();
        for (Db.Reservation r : all) if (r.userId.equals(u.id)) mine.add(r);
        mine.sort(Comparator.comparing((Db.Reservation r) -> r.date).reversed());
        StringBuilder sb = new StringBuilder("<h2>My Reservations (CRUD)</h2>");
        if (msg != null && !msg.isEmpty()) sb.append("<p class='ok'>").append(Html.esc(msg)).append("</p>");
        sb.append("<p><a class='btn' href='/book'>+ New booking</a></p>");
        if (mine.isEmpty()) return sb.append("<p class='muted'>No reservations yet.</p>").toString();
        sb.append("<div class='tablewrap'><table><tr><th>ID</th><th>Sport</th><th>Court</th><th>Date</th><th>Time</th><th>Hrs</th><th>Price</th><th>Status</th><th>Actions</th></tr>");
        for (Db.Reservation r : mine) {
            sb.append("<tr><td>").append(r.id).append("</td><td>").append(Html.cap(r.sport)).append("</td><td>").append(r.court).append("</td><td>").append(r.date).append("</td><td>").append(r.start).append("–").append(r.end).append("</td><td>").append((int) r.hours).append("</td><td>₱").append(r.price).append("</td><td>").append(Html.cap(r.status)).append("</td><td class='acts'>");
            if (r.status.equals("confirmed")) {
                sb.append("<a class='btn xs' href='/edit?id=").append(r.id).append("'>Edit</a> ");
                sb.append("<form style='display:inline' method='POST' action='/cancel' onsubmit='return confirmDel(\"Ask admin to cancel ").append(r.id).append("? Refund comes after admin confirms.\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs warn' type='submit'>Cancel</button></form> ");
            }
            if (r.status.equals("pending")) {
                sb.append("<span class='muted'>Waits for admin</span> ");
                sb.append("<form style='display:inline' method='POST' action='/takeback'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs' type='submit'>Take back</button></form>");
            }
            if (r.status.equals("cancelled"))
                sb.append("<form style='display:inline' method='POST' action='/delete' onsubmit='return confirmDel(\"Permanently delete ").append(r.id).append("?\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs danger' type='submit'>Delete</button></form>");
            sb.append("</td></tr>");
        }
        return sb.append("</table></div>").toString();
    }

    static void editGet(HttpExchange ex, Db.User u, Map<String, String> q) throws IOException {
        Db.Reservation r = null;
        for (Db.Reservation x : Db.loadReservations()) if (x.id.equals(q.getOrDefault("id", ""))) r = x;
        if (r == null) { send(ex, 404, "Not found", "text/html"); return; }
        if (!r.userId.equals(u.id)) { send(ex, 403, "Not yours", "text/html"); return; }
        int startHour = 9;
        try { startHour = Integer.parseInt(r.start.split(":")[0]); } catch (Exception ignored) {}
        String body = "<h2>Edit " + r.id + "</h2><form class='form' method='POST' action='/edit'>"
            + "<input type='hidden' name='id' value='" + r.id + "'>"
            + sportSelect(r.sport)
            + "<label>Court<input name='court' value='" + r.court + "' maxlength='1' required></label>"
            + "<label>Date<input type='date' name='date' value='" + r.date + "' required></label>"
            + hourStartSelect(r.start)
            + durationSelect(String.valueOf((int) r.hours), startHour)
            + "<button class='btn big' type='submit'>Save changes</button></form><p><a href='/my-bookings'>Back</a></p>";
        returnHtml(ex, Html.layout("Edit", body, u));
    }

    static void editPost(HttpExchange ex, Db.User u, Map<String, String> f) throws IOException {
        String id = f.getOrDefault("id", "");
        List<Db.Reservation> all = Db.loadReservations();
        Db.Reservation r = null;
        for (Db.Reservation x : all) if (x.id.equals(id)) r = x;
        if (r == null) { send(ex, 404, "Not found", "text/html"); return; }
        if (!r.userId.equals(u.id)) { send(ex, 403, "Not yours", "text/html"); return; }
        String sport = f.getOrDefault("sport", r.sport).toLowerCase(), court = f.getOrDefault("court", r.court).toUpperCase();
        String date = f.getOrDefault("date", r.date), start = f.getOrDefault("start", r.start);
        int dur = 0;
        try { dur = Integer.parseInt(f.getOrDefault("hours", "0")); } catch (Exception ignored) {}
        String end = "";
        try { end = fmtTime(toMin(start) + dur * 60); } catch (Exception ignored) {}
        Db.User owner = Db.findById(r.userId);
        String err = dur < 1 ? "Pick how many hours (at least 1)." : validateBooking(owner == null ? u : owner, sport, court, date, start, end, r.id);
        Map<String, Integer> rates = Db.loadRates();
        double hrs = 0; int price = r.price;
        try { hrs = (toMin(end) - toMin(start)) / 60.0; price = (int) Math.round(rates.get(sport) * hrs); } catch (Exception ignored) {}
        if (err == null && price > r.price && owner != null) {
            Db.User fresh = Db.findById(owner.id);
            if (fresh.balance < (price - r.price)) err = "Insufficient balance for price increase: need extra ₱" + (price - r.price) + ".";
        }
        if (err != null) { returnHtml(ex, Html.layout("Edit", "<p class='err'>" + Html.esc(err) + "</p><p><a href='/edit?id=" + r.id + "'>Back</a></p>", u)); return; }
        int diff = price - r.price;
        if (diff != 0 && owner != null) {
            List<Db.User> users = Db.loadUsers();
            for (Db.User x : users) if (x.id.equals(owner.id)) x.balance -= diff;
            Db.saveUsers(users);
            Db.addTxn(owner.id, diff > 0 ? "pay" : "refund", -diff, "Reschedule " + r.id + " price adj.");
        }
        r.sport = sport; r.court = court; r.date = date; r.start = start; r.end = end; r.hours = hrs; r.price = price;
        Db.saveReservations(all);
        redirect(ex, "/my-bookings");
    }

    // wallet page: balance plus history
    static String wallet(Db.User u) throws IOException {
        Db.User fresh = Db.findById(u.id);
        StringBuilder sb = new StringBuilder("<h2>Wallet</h2><p>Balance: <b>₱" + fresh.balance + "</b></p>"
            + "<p class='muted'>Top-ups are done by admin. Cancel refunds come after the admin confirms.</p><div class='tablewrap'><table><tr><th>Date</th><th>Type</th><th>Amount</th><th>Note</th></tr>");
        List<Db.Txn> tx = Db.loadTxns();
        tx.sort(Comparator.comparing((Db.Txn t) -> t.date).reversed());
        for (Db.Txn t : tx) if (t.userId.equals(u.id))
            sb.append("<tr><td>").append(t.date.substring(0, 16).replace("T", " ")).append("</td><td>").append(Html.cap(t.type)).append("</td><td>").append(t.amount >= 0 ? "+" : "").append(t.amount).append("</td><td>").append(Html.esc(t.note)).append("</td></tr>");
        return sb.append("</table></div>").toString();
    }

    // admin pages: all bookings, users and balance, prices
    static String adminPage(Map<String, String> q) throws IOException {
        String tab = q.getOrDefault("tab", "reservations");
        Map<String, Integer> rates = Db.loadRates();
        StringBuilder sb = new StringBuilder("<h2>Admin Panel</h2>");
        if (q.containsKey("msg")) sb.append("<p class='err'>").append(Html.esc(q.get("msg"))).append("</p>");
        sb.append("<p class='tabs'>"
            + "<a class='" + (tab.equals("reservations") ? "on" : "") + "' href='/admin?tab=reservations'>Reservations</a>"
            + "<a class='" + (tab.equals("users") ? "on" : "") + "' href='/admin?tab=users'>Users & Balance</a>"
            + "<a class='" + (tab.equals("rates") ? "on" : "") + "' href='/admin?tab=rates'>Rates</a></p>");
        if (tab.equals("users")) {
            sb.append("<div class='tablewrap'><table><tr><th>Name</th><th>Email</th><th>Role</th><th>Balance</th><th>Top-up</th><th>Password</th></tr>");
            for (Db.User x : Db.loadUsers()) {
                if (x.role.equals("admin")) continue;
                sb.append("<tr><td>").append(Html.esc(x.name)).append("</td><td>").append(Html.esc(x.email)).append("</td><td>").append(Html.cap(x.role)).append("</td><td>₱").append(x.balance).append("</td><td>")
                  .append("<form class='inline' method='POST' action='/admin/topup'><input type='hidden' name='userId' value='").append(x.id).append("'><input name='amount' type='number' placeholder='+500' required style='width:90px'><button class='btn xs' type='submit'>Apply</button></form></td><td>")
                  .append("<form class='inline' method='POST' action='/admin/password'><input type='hidden' name='userId' value='").append(x.id).append("'><span class='muted'>").append("•".repeat(Math.min(Math.max(x.passLen, 0), 32))).append("</span> <input name='newpass' type='text' placeholder='new password' required minlength='6' style='width:110px'><button class='btn xs' type='submit'>Set</button></form></td></tr>");
            }
            sb.append("</table></div><p class='muted'>Positive = add funds, negative = deduct. Balance can never go below 0.</p>");
        } else if (tab.equals("rates")) {
            sb.append("<form class='form' method='POST' action='/admin/rate'>");
            for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
                sb.append("<label>").append(s).append(" (₱/hr)<input name='").append(s).append("' type='number' min='0' value='").append(rates.get(s)).append("' required></label>");
            sb.append("<button class='btn' type='submit'>Save rates</button></form>");
        } else {
            sb.append("<div class='tablewrap'><table><tr><th>ID</th><th>User</th><th>Sport</th><th>Court</th><th>Date</th><th>Time</th><th>Price</th><th>Status</th><th>Actions</th></tr>");
            List<Db.Reservation> all = Db.loadReservations();
            all.sort(Comparator.comparing((Db.Reservation r) -> r.date).thenComparing(r -> r.start));
            for (Db.Reservation r : all) {
                Db.User owner = Db.findById(r.userId);
                sb.append("<tr><td>").append(r.id).append("</td><td>").append(owner == null ? "?" : Html.esc(owner.email)).append("</td><td>").append(Html.cap(r.sport)).append("</td><td>").append(r.court).append("</td><td>").append(r.date).append("</td><td>").append(r.start).append("–").append(r.end).append("</td><td>₱").append(r.price).append("</td><td>").append(Html.cap(r.status)).append("</td><td class='acts'>");
                if (r.status.equals("confirmed"))
                    sb.append("<form style='display:inline' method='POST' action='/admin/cancel' onsubmit='return confirmDel(\"Cancel ").append(r.id).append("? ₱").append(r.price).append(" refunded to user.\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs warn' type='submit'>Cancel+Refund</button></form> ");
                if (r.status.equals("pending")) {
                    sb.append("<form style='display:inline' method='POST' action='/admin/cancel' onsubmit='return confirmDel(\"Confirm cancel of ").append(r.id).append("? ₱").append(r.price).append(" refunded to user.\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs warn' type='submit'>Confirm cancel</button></form> ");
                    sb.append("<form style='display:inline' method='POST' action='/admin/keep'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs' type='submit'>Keep</button></form> ");
                }
                sb.append("<form style='display:inline' method='POST' action='/admin/delete' onsubmit='return confirmDel(\"Permanently delete ").append(r.id).append("?\")'><input type='hidden' name='id' value='").append(r.id).append("'><button class='btn xs danger' type='submit'>Delete</button></form>");
                sb.append("</td></tr>");
            }
            sb.append("</table></div>");
        }
        return sb.toString();
    }

    static void adminKeep(HttpExchange ex, Map<String, String> f) throws IOException {
        List<Db.Reservation> all = Db.loadReservations();
        for (Db.Reservation r : all) {
            if (r.id.equals(f.getOrDefault("id", "")) && r.status.equals("pending")) {
                r.status = "confirmed";
                // tell the customer their cancel request was declined
                Db.addTxn(r.userId, "info", 0, "Admin kept booking " + r.id + ", cancel request declined");
            }
        }
        Db.saveReservations(all);
        redirect(ex, "/admin");
    }
    static void adminHardDelete(HttpExchange ex, Map<String, String> f) throws IOException {
        List<Db.Reservation> all = Db.loadReservations();
        final String id = f.getOrDefault("id", "");
        all.removeIf(r -> r.id.equals(id));
        Db.saveReservations(all);
        redirect(ex, "/admin");
    }
    // profile page: change name and password
    static String profileForm(Db.User u, String err, String ok) {
        String[] np = u.name.split(" ", 2);
        String fn = np[0], ln = np.length > 1 ? np[1] : "";
        StringBuilder sb = new StringBuilder("<h2>Profile</h2>");
        if (err != null) sb.append("<p class='err'>").append(Html.esc(err)).append("</p>");
        if (ok != null) sb.append("<p class='ok'>").append(Html.esc(ok)).append("</p>");
        sb.append("<form class='form' method='POST' action='/profile'>"
            + "<label>First name<input name='firstname' required value='" + Html.esc(fn) + "'></label>"
            + "<label>Last name<input name='lastname' required value='" + Html.esc(ln) + "'></label>"
            + "<label>Current password (needed to save changes)<input name='current' type='password' required></label>"
            + "<label>New password (leave blank to keep)<input name='newpass' type='password' minlength='6'></label>"
            + "<button class='btn big' type='submit'>Save changes</button></form>");
        return sb.toString();
    }

    static void doProfile(HttpExchange ex, Db.User user, Map<String, String> f) throws IOException {
        Db.User fresh = Db.findById(user.id);
        if (fresh == null) { redirect(ex, "/login"); return; }
        String fn = f.getOrDefault("firstname", "").trim().replaceAll("\\s+", " ");
        String ln = f.getOrDefault("lastname", "").trim().replaceAll("\\s+", " ");
        String cur = f.getOrDefault("current", ""), npw = f.getOrDefault("newpass", "");
        String err = null;
        if (!Auth.verify(cur, fresh.salt, fresh.hash)) err = "Current password is wrong.";
        else if (fn.isEmpty() || ln.isEmpty()) err = "Enter your first name and last name.";
        else if (!npw.isEmpty() && npw.length() < 6) err = "New password must be at least 6 characters.";
        else {
            for (Db.User x : Db.loadUsers()) {
                if (!x.id.equals(fresh.id) && x.name.equalsIgnoreCase(fn + " " + ln)) { err = "That name is already registered."; break; }
            }
        }
        if (err != null) { returnHtml(ex, Html.layout("Profile", profileForm(fresh, err, null), user)); return; }
        List<Db.User> users = Db.loadUsers();
        boolean nameChanged = !(fn + " " + ln).equalsIgnoreCase(fresh.name);
        boolean pwChanged = !npw.isEmpty();
        for (Db.User x : users) {
            if (x.id.equals(fresh.id)) {
                x.name = fn + " " + ln;
                if (pwChanged) { x.salt = Auth.genSalt(); x.hash = Auth.hash(npw, x.salt); x.passLen = npw.length(); }
            }
        }
        Db.saveUsers(users);
        String what = nameChanged && pwChanged ? "Name and password updated." : nameChanged ? "Name updated." : pwChanged ? "Password updated." : "No changes.";
        returnHtml(ex, Html.layout("Profile", profileForm(Db.findById(fresh.id), null, what), user));
    }

    static void adminPassword(HttpExchange ex, Map<String, String> f) throws IOException {
        String uid = f.getOrDefault("userId", ""), npw = f.getOrDefault("newpass", "");
        if (npw.length() < 6) { redirect(ex, "/admin?tab=users&msg=" + URLEncoder.encode("New password must be at least 6 characters.", "UTF-8")); return; }
        List<Db.User> users = Db.loadUsers();
        Db.User target = null;
        for (Db.User x : users) if (x.id.equals(uid) && !x.role.equals("admin")) target = x;
        if (target == null) { redirect(ex, "/admin?tab=users"); return; }
        target.salt = Auth.genSalt();
        target.hash = Auth.hash(npw, target.salt);
        target.passLen = npw.length();
        Db.saveUsers(users);
        Db.addTxn(uid, "info", 0, "Admin reset the password");
        redirect(ex, "/admin?tab=users&msg=" + URLEncoder.encode("Password for " + target.email + " is now: " + npw, "UTF-8"));
    }

    static void adminTopup(HttpExchange ex, Map<String, String> f) throws IOException {
        String uid = f.getOrDefault("userId", ""); int amt;
        try { amt = Integer.parseInt(f.getOrDefault("amount", "0").trim()); } catch (Exception e) { redirect(ex, "/admin?tab=users"); return; }
        List<Db.User> users = Db.loadUsers();
        Db.User target = null;
        for (Db.User x : users) if (x.id.equals(uid)) target = x;
        if (target == null) { redirect(ex, "/admin?tab=users"); return; }
        // no debt: balance can never go below zero
        if (target.balance + amt < 0) {
            redirect(ex, "/admin?tab=users&msg=" + URLEncoder.encode("Blocked: balance cannot go below 0.", "UTF-8")); return;
        }
        target.balance += amt;
        Db.saveUsers(users);
        Db.addTxn(uid, amt >= 0 ? "topup" : "deduct", amt, "Admin balance update");
        redirect(ex, "/admin?tab=users");
    }
    static void adminRate(HttpExchange ex, Map<String, String> f) throws IOException {
        Map<String, Integer> m = Db.loadRates();
        for (String s : List.of("basketball", "volleyball", "badminton", "billiard"))
            try { m.put(s, Math.max(0, Integer.parseInt(f.getOrDefault(s, String.valueOf(m.get(s))).trim()))); } catch (Exception ignored) {}
        Db.saveRates(m);
        redirect(ex, "/admin?tab=rates");
    }

// request helpers
    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            try { m.put(URLDecoder.decode(p[0], "UTF-8"), p.length > 1 ? URLDecoder.decode(p[1], "UTF-8") : ""); } catch (Exception ignored) {}
        }
        return m;
    }
    static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        return parseQuery(body);
    }
    static Map<String, String> toMap(Map<String, String> f) { return new HashMap<>(f); }
    static String cookie(HttpExchange ex, String name) {
        List<String> cl = ex.getRequestHeaders().get("Cookie");
        if (cl == null) return null;
        for (String c : cl) for (String kv : c.split(";")) {
            String[] p = kv.trim().split("=", 2);
            if (p.length == 2 && p[0].trim().equals(name)) return p[1].trim();
        }
        return null;
    }
    static void send(HttpExchange ex, int code, String body, String ct) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", ct.contains("charset") ? ct : ct + "; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
    static void sendBytes(HttpExchange ex, int code, byte[] b, String ct) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
    static void returnHtml(HttpExchange ex, String html) throws IOException { send(ex, 200, html, "text/html"); }
    static void redirect(HttpExchange ex, String loc) throws IOException {
        ex.getResponseHeaders().add("Location", loc);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
}
