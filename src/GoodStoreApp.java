import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public class GoodStoreApp {

    // Кэш тепер прив'язує дані до конкретного email, щоб уникнути конфліктів
    static Map<String, String> cache = new HashMap<>();
    static String lastUserEmail = null;

    static final String DB_URL = "jdbc:postgresql://localhost:5432/app";

    // Строгі змінні оточення без небезпечних хардкодів для продакшену
    static final String DB_USER = System.getenv("DB_USER");
    static final String DB_PASS = System.getenv("DB_PASS");

    // "Сіль" для хешування (в ідеалі має генеруватися випадково для кожного користувача і зберігатися в БД)
    static final String STATIC_SALT = "S0m3R@nd0mS@lt!";

    public static void main(String[] args) {
        if (DB_USER == null || DB_PASS == null) {
            System.err.println("CRITICAL: DB_USER or DB_PASS environment variables are not set.");
            System.exit(1);
        }

        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Welcome!");

            while (true) {
                System.out.println("\n1 - login, 2 - buy, 3 - report, 4 - logout, 0 - exit");
                System.out.print("Command: ");
                String cmd = sc.nextLine();

                if (cmd.equals("0")) break;

                if (cmd.equals("1")) {
                    System.out.print("email: ");
                    String email = sc.nextLine();

                    System.out.print("password: ");
                    String password = sc.nextLine();

                    System.out.println("[DEBUG] login attempt for email=" + email);

                    // Звіряємо хеші, а не паролі в чистому вигляді
                    String hash = hashPasswordSafely(password);
                    boolean ok = login(email, hash);

                    if (ok) {
                        lastUserEmail = email; // Змінюємо користувача тільки при успішному вході
                        System.out.println("Login successful!");
                    } else {
                        System.out.println("Invalid email or password.");
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
                        // ВИПРАВЛЕНО: Блокуємо від'ємну і нульову кількість
                        if (qty <= 0) {
                            System.out.println("Quantity must be greater than zero.");
                            continue;
                        }
                        buy(productId, qty);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid quantity format. Please enter a number.");
                    }

                } else if (cmd.equals("3")) {
                    if (lastUserEmail == null) {
                        System.out.println("Please login first!");
                        continue;
                    }
                    String rep = buildReport(lastUserEmail);
                    System.out.println(rep);

                } else if (cmd.equals("4")) {
                    lastUserEmail = null;
                    System.out.println("Logged out successfully.");
                } else {
                    System.out.println("Unknown command");
                }
            }
        }
    }

    // Централізоване отримання з'єднання (в ідеалі тут має бути Connection Pool, напр. HikariCP)
    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    static boolean login(String email, String passwordHash) {
        String sql = "SELECT role FROM users WHERE email = ? AND password_hash = ?";

        try (Connection c = getConnection();
             PreparedStatement pst = c.prepareStatement(sql)) {

            pst.setString(1, email);
            pst.setString(2, passwordHash);

            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    // ВИПРАВЛЕНО: Зберігаємо роль ізольовано для кожного email
                    cache.put(email + ":role", rs.getString("role"));
                    return true;
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error during login: " + e.getMessage());
        }
        return false;
    }

    static void buy(String productId, int qty) {
        BigDecimal price = getPrice(productId);
        if (price == null) {
            System.out.println("Product not found.");
            return;
        }

        // ВИПРАВЛЕНО: Використання BigDecimal для точного розрахунку грошей
        BigDecimal total = price.multiply(BigDecimal.valueOf(qty));
        BigDecimal discountThreshold = new BigDecimal("1000.00");

        if (total.compareTo(discountThreshold) > 0) {
            BigDecimal discount = new BigDecimal("7.00");
            total = total.subtract(discount);
            System.out.println("Discount applied!");
        }

        String sql = "INSERT INTO orders(email, product_id, qty, total) VALUES (?, ?, ?, ?)";

        try (Connection c = getConnection();
             PreparedStatement pst = c.prepareStatement(sql)) {

            pst.setString(1, lastUserEmail);
            pst.setString(2, productId);
            pst.setInt(3, qty);
            pst.setBigDecimal(4, total);

            int rows = pst.executeUpdate();
            if (rows > 0) {
                System.out.println("Bought successfully! Total: " + total);
            }

        } catch (SQLException e) {
            System.err.println("Database error during purchase: " + e.getMessage());
        }
    }

    static BigDecimal getPrice(String productId) {
        String sql = "SELECT price FROM products WHERE id = ?";

        try (Connection c = getConnection();
             PreparedStatement pst = c.prepareStatement(sql)) {

            pst.setString(1, productId);

            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal("price");
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error while fetching price: " + e.getMessage());
        }
        // Замість магічного числа -1 повертаємо null, якщо товару немає
        return null;
    }

    static String buildReport(String email) {
        if (email == null || email.trim().isEmpty()) {
            return "empty";
        }

        // Отримуємо роль конкретного користувача
        String role = cache.getOrDefault(email + ":role", "guest");
        int limit = role.equals("admin") ? 50 : 10;

        StringBuilder sb = new StringBuilder();
        sb.append("\n--- Report for ").append(email).append(" ---\n");
        sb.append("Role: ").append(role).append("\n");

        // ВИПРАВЛЕНО: Обмеження вибірки (LIMIT) на рівні БД, а не в пам'яті
        // Додано LIMIT + 1, щоб перевірити, чи є ще записи понад ліміт
        String sql = "SELECT product_id, qty, total FROM orders WHERE email = ? ORDER BY id DESC LIMIT ?";

        try (Connection c = getConnection();
             PreparedStatement pst = c.prepareStatement(sql)) {

            pst.setString(1, email);
            pst.setInt(2, limit + 1);

            try (ResultSet rs = pst.executeQuery()) {
                int count = 1;
                while (rs.next()) {
                    if (count > limit) {
                        sb.append("...and more\n");
                        break;
                    }
                    sb.append(count).append(") Product: ")
                            .append(rs.getString("product_id"))
                            .append(" | Qty: ").append(rs.getInt("qty"))
                            .append(" | Total: ").append(rs.getBigDecimal("total"))
                            .append("\n");
                    count++;
                }
                if (count == 1) {
                    sb.append("No orders found.\n");
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error while building report: " + e.getMessage());
            return "Error generating report.";
        }

        sb.append("---------------------------");
        return sb.toString();
    }

    static String hashPasswordSafely(String plainText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Додаємо "сіль" для захисту від Rainbow Tables
            String saltedPassword = plainText + STATIC_SALT;
            byte[] hash = md.digest(saltedPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }
}
//# 1. Переходимо в папку, де знаходиться скомпільований код програми
//cd "/Users/dimagordeev/Солнышко/untitled6/out/production/untitled6"
//
//        # 2. Передаємо терміналу секретні дані (експортуємо змінні середовища для БД)
//export DB_USER="postgres"
//export DB_PASS="12345"
//
//# 3. Запускаємо програму
//java GoodStoreApp