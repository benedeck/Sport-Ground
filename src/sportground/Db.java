package sportground;

import java.io.*;
import java.nio.file.*;
import java.util.*;

// saves everything as text files inside ./data
// each line is one row, fields split by |
public class Db {
    public static final String DATA_DIR = "data";
    public static final String USERS_FILE = DATA_DIR + "/users.txt";
    public static final String RES_FILE = DATA_DIR + "/reservations.txt";
    public static final String TXN_FILE = DATA_DIR + "/transactions.txt";
    public static final String RATES_FILE = DATA_DIR + "/rates.txt";

    // users table
    public static class User {
        public String id, name, email, hash, salt, role;
        public int balance, passLen;
        public User(String id, String name, String email, String hash, String salt, String role, int balance, int passLen) {
            this.id = id; this.name = name; this.email = email;
            this.hash = hash; this.salt = salt; this.role = role; this.balance = balance; this.passLen = passLen;
        }
    }

    // reservations table
    public static class Reservation {
        public String id, userId, sport, court, date, start, end, status, createdAt;
        public double hours;
        public int price;
        public Reservation(String id, String userId, String sport, String court, String date,
                           String start, String end, double hours, int price, String status, String createdAt) {
            this.id = id; this.userId = userId; this.sport = sport; this.court = court;
            this.date = date; this.start = start; this.end = end;
            this.hours = hours; this.price = price; this.status = status; this.createdAt = createdAt;
        }
    }

    // wallet history table
    public static class Txn {
        public String id, userId, type, note, date;
        public int amount;
        public Txn(String id, String userId, String type, int amount, String note, String date) {
            this.id = id; this.userId = userId; this.type = type;
            this.amount = amount; this.note = note; this.date = date;
        }
    }

    static synchronized void init() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));
        for (String f : new String[]{USERS_FILE, RES_FILE, TXN_FILE}) {
            if (!Files.exists(Paths.get(f))) Files.writeString(Paths.get(f), "");
        }
        if (!Files.exists(Paths.get(RATES_FILE))) {
            Files.writeString(Paths.get(RATES_FILE), "basketball=300\nvolleyball=200\nbadminton=200\nbilliard=150\n");
        }
    // first run: add admin and demo accounts
        if (loadUsers().isEmpty()) {
            String s1 = Auth.genSalt(), s2 = Auth.genSalt();
            List<User> seed = new ArrayList<>();
            seed.add(new User(nextId("U"), "Admin", "admin@sportground.ph", Auth.hash("admin123", s1), s1, "admin", 0, 8));
            seed.add(new User(nextId("U"), "Demo User", "user@sportground.ph", Auth.hash("user123", s2), s2, "customer", 2000, 7));
            saveUsers(seed);
        }
    }

    static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("|", "").replace("\n", " ").trim(); }
    static String[] split(String line) { return line.split("\\|", -1); }

    public static synchronized List<User> loadUsers() throws IOException {
        List<User> list = new ArrayList<>();
        if (!Files.exists(Paths.get(USERS_FILE))) return list;
        for (String line : Files.readAllLines(Paths.get(USERS_FILE))) {
            if (line.isBlank()) continue;
            String[] p = split(line);
            if (p.length < 7) continue;
            int pl = 0;
            if (p.length >= 8) { try { pl = Integer.parseInt(p[7]); } catch (Exception ignored) {} }
            list.add(new User(p[0], p[1], p[2], p[3], p[4], p[5], Integer.parseInt(p[6]), pl));
        }
        return list;
    }

    public static synchronized void saveUsers(List<User> list) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (User u : list) sb.append(String.join("|", esc(u.id), esc(u.name), esc(u.email), u.hash, u.salt, u.role, String.valueOf(u.balance), String.valueOf(u.passLen))).append("\n");
        Files.writeString(Paths.get(USERS_FILE), sb.toString());
    }

    public static synchronized List<Reservation> loadReservations() throws IOException {
        List<Reservation> list = new ArrayList<>();
        if (!Files.exists(Paths.get(RES_FILE))) return list;
        for (String line : Files.readAllLines(Paths.get(RES_FILE))) {
            if (line.isBlank()) continue;
            String[] p = split(line);
            if (p.length < 11) continue;
            list.add(new Reservation(p[0], p[1], p[2], p[3], p[4], p[5], p[6],
                    Double.parseDouble(p[7]), Integer.parseInt(p[8]), p[9], p[10]));
        }
        return list;
    }

    public static synchronized void saveReservations(List<Reservation> list) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Reservation r : list) sb.append(String.join("|", esc(r.id), esc(r.userId), esc(r.sport), esc(r.court),
                esc(r.date), esc(r.start), esc(r.end), String.valueOf(r.hours), String.valueOf(r.price), esc(r.status), esc(r.createdAt))).append("\n");
        Files.writeString(Paths.get(RES_FILE), sb.toString());
    }

    public static synchronized List<Txn> loadTxns() throws IOException {
        List<Txn> list = new ArrayList<>();
        if (!Files.exists(Paths.get(TXN_FILE))) return list;
        for (String line : Files.readAllLines(Paths.get(TXN_FILE))) {
            if (line.isBlank()) continue;
            String[] p = split(line);
            if (p.length < 6) continue;
            list.add(new Txn(p[0], p[1], p[2], Integer.parseInt(p[3]), p[4], p[5]));
        }
        return list;
    }

    public static synchronized void addTxn(String userId, String type, int amount, String note) throws IOException {
        String line = String.join("|", nextId("T"), esc(userId), esc(type), String.valueOf(amount), esc(note), java.time.LocalDateTime.now().toString()) + "\n";
        Files.writeString(Paths.get(TXN_FILE), line, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
    }

    // prices per hour for each sport
    public static synchronized Map<String, Integer> loadRates() throws IOException {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("basketball", 300); m.put("volleyball", 200); m.put("badminton", 200); m.put("billiard", 150);
        for (String line : Files.readAllLines(Paths.get(RATES_FILE))) {
            if (line.isBlank() || !line.contains("=")) continue;
            String[] kv = line.split("=", 2);
            try { m.put(kv[0].trim().toLowerCase(), Integer.parseInt(kv[1].trim())); } catch (Exception ignored) {}
        }
        return m;
    }

    public static synchronized void saveRates(Map<String, Integer> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
        Files.writeString(Paths.get(RATES_FILE), sb.toString());
    }

    static String nextId(String prefix) { return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }

    public static User findByEmail(String email) throws IOException {
        for (User u : loadUsers()) if (u.email.equalsIgnoreCase(email)) return u;
        return null;
    }
    public static User findById(String id) throws IOException {
        for (User u : loadUsers()) if (u.id.equals(id)) return u;
        return null;
    }
}
