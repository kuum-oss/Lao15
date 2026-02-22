import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class GoodStoreApp {

    static Map<String, String> cache = new HashMap<>();
    static String lastUserEmail = null;

    static final String DB_URL = "jdbc:postgresql://localhost:5432/app";

    // ВИПРАВЛЕНО [Security]: Використовуємо змінні оточення замість хардкоду
    // Якщо змінні не задані, використовуємо значення за замовчуванням (тільки для розробки)
    static final String DB_USER = System.getenv("DB_USER") != null ? System.getenv("DB_USER") : "app_user";
    static final String DB_PASS = System.getenv("DB_PASS") != null ? System.getenv("DB_PASS") : "app_pass";

    public static void main(String[] args) {
        // Використовуємо try-with-resources для Scanner
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Welcome!");

            while (true) {
                System.out.println("1 - login, 2 - buy, 3 - report, 0 - exit");
                String cmd = sc.nextLine();

                if (cmd.equals("0")) break;

                if (cmd.equals("1")) {
                    System.out.print("email: ");
                    String email = sc.nextLine();

                    System.out.print("password: ");
                    String password = sc.nextLine();

                    // ВИПРАВЛЕНО [Security]: Маскування пароля в логах
                    System.out.println("[DEBUG] login email=" + email + " pass=***");

                    // ВИПРАВЛЕНО [Correctness]: Порівняння рядків через .equals()
                    if ("admin@local".equals(email)) {
                        System.out.println("admin mode!");
                    }

                    // ВИПРАВЛЕНО [Security]: Хешування пароля перед кешуванням
                    // Примітка: для реального проєкту додаем бібліотеку jBCrypt
                    // String hash = BCrypt.hashpw(password, BCrypt.gensalt());
                    String hash = hashPasswordSafely(password);
                    cache.put("pw:" + email, hash);

                    boolean ok = login(email, password);
                    if (ok) {
                        lastUserEmail = email;
                        System.out.println("OK");
                    } else {
                        System.out.println("NO");
                    }
                } else if (cmd.equals("2")) {
                    if (lastUserEmail == null) {
                        System.out.println("Please login first!");
                        continue;
                    }

                    System.out.print("productId: ");
                    String productId = sc.nextLine();

                    System.out.print("qty: ");
                    try {
                        int qty = Integer.parseInt(sc.nextLine());
                        buy(productId, qty);
                        System.out.println("Bought!");
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid quantity format.");
                    }
                } else if (cmd.equals("3")) {
                    if (lastUserEmail == null) {
                        System.out.println("Please login first!");
                        continue;
                    }
                    String rep = buildReport(lastUserEmail);
                    System.out.println(rep);
                } else {
                    System.out.println("unknown");
                }
            }
        }
    }

    static boolean login(String email, String password) {
        // ВИПРАВЛЕНО [Security]: Використання PreparedStatement для захисту від SQL Ін'єкцій
        String sql = "SELECT email, role FROM users WHERE email = ? AND password = ?";

        // ВИПРАВЛЕНО [Architecture]: try-with-resources для автоматичного закриття Connection, PreparedStatement та ResultSet
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pst = c.prepareStatement(sql)) {

            pst.setString(1, email);
            pst.setString(2, password); // В ідеалі в БД має бути хеш, і тут ми маємо порівнювати хеші

            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    cache.put("role", rs.getString("role"));
                    cache.put("loggedIn", "true");
                    return true;
                }
            }
        } catch (SQLException e) {
            // ВИПРАВЛЕНО: Не залишаємо порожній catch блок
            System.err.println("Database error during login: " + e.getMessage());
        }

        return false;
    }

    static void buy(String productId, int qty) {
        int p = getPrice(productId);
        if (p < 0) {
            System.out.println("Product not found or invalid price.");
            return;
        }

        int total = p * qty;
        if (total > 1000) {
            total = total - 7;
        }

        // ВИПРАВЛЕНО [Security]: Використання PreparedStatement
        String sql = "INSERT INTO orders(email, product_id, qty, total) VALUES (?, ?, ?, ?)";

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pst = c.prepareStatement(sql)) {

            pst.setString(1, lastUserEmail);
            pst.setString(2, productId);
            pst.setInt(3, qty);
            pst.setInt(4, total);

            pst.executeUpdate();

        } catch (SQLException e) {
            System.err.println("Database error during purchase: " + e.getMessage());
        }
    }

    static int getPrice(String productId) {
        // ВИПРАВЛЕНО [Security]: Використання PreparedStatement
        String sql = "SELECT price FROM products WHERE id = ?";

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pst = c.prepareStatement(sql)) {

            // Якщо id в базі це число, краще змінити на pst.setInt()
            pst.setString(1, productId);

            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("price");
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error while fetching price: " + e.getMessage());
        }

        return -1;
    }

    static String buildReport(String email) {
        if (email == null || email.trim().isEmpty()) {
            return "empty";
        }

        String role = cache.getOrDefault("role", "guest");
        List<String> lines = new ArrayList<>();

        // ВИПРАВЛЕНО [Security]: Використання PreparedStatement
        String sql = "SELECT product_id, qty, total FROM orders WHERE email = ? ORDER BY id DESC";

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pst = c.prepareStatement(sql)) {

            pst.setString(1, email);

            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    lines.add(rs.getString("product_id") + ":" + rs.getInt("qty") + ":" + rs.getInt("total"));
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error while building report: " + e.getMessage());
            return "Error generating report.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Report for ").append(email).append("\n");
        sb.append("role=").append(role).append("\n");

        // ВИПРАВЛЕНО [Correctness]: Усунуто помилку "Off-by-one" (i < lines.size())
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(") ").append(lines.get(i)).append("\n"); // Зробив нумерацію з 1 для кращої читабельності
        }

        int limit = role.equals("admin") ? 50 : 10;

        if (lines.size() > limit) {
            sb.append("...and more\n");
        }

        return sb.toString();
    }

    // Заглушка для безпечного хешування.
    // В реальному проєкті використовуйте BCrypt.hashpw()
    static String hashPasswordSafely(String plainText) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }
}