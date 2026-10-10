package sportground;

import java.util.*;

// all the pages live here, styling comes from /static/style.css
public class Html {
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // first letter big, the rest small: basketball -> Basketball
    public static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    public static String layout(String title, String body, Db.User user) {
        String nav;
        if (user == null) {
            nav = "<a href='/login'>Login</a><a class='btn' href='/register'>Register</a>";
        } else if (user.role.equals("admin")) {
            nav = "<a href='/admin'>Admin</a><a href='/logout'>Logout</a>";
        } else {
            nav = "<span class='bal'>Hi, " + esc(user.name) + " • ₱" + user.balance + "</span>"
                + "<a href='/dashboard'>Dashboard</a><a href='/my-bookings'>My Bookings</a><a href='/wallet'>Wallet</a><a href='/profile'>Profile</a><a href='/logout'>Logout</a>";
        }
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>" + esc(title) + " — Sportground</title><link rel='stylesheet' href='/static/style.css'></head><body>"
            + "<header><div class='wrap nav'><a class='logo' href='/'>🏟️ Sportground</a><nav>" + nav + "</nav></div></header>"
            + "<main class='wrap'>" + body + "</main>"
            + "<footer><div class='wrap'>Sportground • Open 8AM–12AM daily • Manila, PH • walk-ins + online reservation</div></footer>"
            + "<script>function confirmDel(m){return confirm(m||'Are you sure? This cannot be undone.');}</script>"
            + "</body></html>";
    }

    // landing page: hero, sports, prices
    public static String landing(Map<String, Integer> rates) {
        return "<section class='hero'><div><h1>Book your game. Own the court.</h1>"
            + "<p>Basketball • Volleyball • Badminton • Billiard. Real-time availability 8AM–12AM, per-hour PH rates, wallet refunds if admin cancels.</p>"
            + "<p><a class='btn big' href='/register'>Book Now</a> <a class='btn ghost' href='/login'>Login</a></p>"
            + "<p class='muted'>Rule: 1 reservation per sport per day. No double-booking — overlapping time on the same court is blocked.</p></div>"
            + "<img src='/static/images/hero.svg' alt='sports facility'></section>"
            + "<h2>Our Sports & Rates (per hour)</h2><div class='grid'>"
            + card("Basketball", rates.get("basketball"), "Full court • Courts A & B • ₱", "/static/images/basketball.svg", "Indoor hardwood, scoreboard, night lights.")
            + card("Volleyball", rates.get("volleyball"), "Court • Courts A & B • ₱", "/static/images/volleyball.svg", "Taraflex court, net + antenna included.")
            + card("Badminton", rates.get("badminton"), "Court • Courts A & B • ₱", "/static/images/badminton.svg", "Pro mats, high ceiling, cool airflow.")
            + card("Billiard", rates.get("billiard"), "Table • Tables A, B & C • ₱", "/static/images/billiard.svg", "Brunswick tables, quiet zone, hourly play.")
            + "</div>"
            + "<h2>How it works</h2><ol class='steps'><li>Register & top-up via admin (wallet).</li><li>Check availability & book (8AM–12AM).</li><li>Pay from balance. Reschedule or cancel from My Bookings.</li><li>Admin cancel due to weather/maintenance = auto-refund to balance.</li></ol>";
    }

    static String card(String sport, int rate, String prefix, String img, String desc) {
        return "<div class='card'><img src='" + img + "' alt='" + sport + "'>"
            + "<h3>" + sport + "</h3><p>" + desc + "</p>"
            + "<p class='price'>" + prefix + rate + "/hr</p>"
            + "<a class='btn' href='/book?sport=" + sport.toLowerCase() + "'>Reserve " + sport + "</a></div>";
    }

    // login form, keeps the typed email and shows the lockout countdown
    public static String loginForm(String err, String email, long lockSecs) {
        if (lockSecs > 0) {
            return "<h2>Login</h2><p class='err'>Too many wrong passwords. Try again in <b id='cd'>" + lockSecs + "</b> seconds.</p>"
                + "<form class='form'><label>Email<input value='" + esc(email) + "' disabled></label>"
                + "<label>Password<input type='password' disabled></label></form>"
                + "<script>let s=" + lockSecs + ";const t=setInterval(()=>{s--;const el=document.getElementById('cd');if(el)el.textContent=s;if(s<=0){clearInterval(t);location.href='/login';}},1000);</script>";
        }
        return "<h2>Login</h2>" + (err == null ? "" : "<p class='err'>" + esc(err) + "</p>")
            + "<form class='form' method='POST' action='/login'>"
            + "<label>Email (ends with @sportground.ph)<input name='email' type='email' required value='" + esc(email) + "'></label>"
            + "<label>Password<input name='password' type='password' required></label>"
            + "<button class='btn big' type='submit'>Login</button></form>"
            + "<p>Demo: admin@sportground.ph / admin123 • user@sportground.ph / user123 • <a href='/register'>Create account</a></p>";
    }

    // register form: first name and last name in two boxes
    public static String registerForm(String err) {
        return "<h2>Register</h2>" + (err == null ? "" : "<p class='err'>" + esc(err) + "</p>")
            + "<form class='form' method='POST' action='/register'>"
            + "<label>First name<input name='firstname' required placeholder='Juan'></label>"
            + "<label>Last name<input name='lastname' required placeholder='Dela Cruz'></label>"
            + "<label>Email (ends with @sportground.ph)<input name='email' type='email' required placeholder='you@sportground.ph'></label>"
            + "<label>Password (min 6)<input name='password' type='password' required minlength='6'></label>"
            + "<button class='btn big' type='submit'>Create account</button></form>";
    }
}
