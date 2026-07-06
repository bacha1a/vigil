package io.vigil.jdbc;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public enum SqlDialect {

    DEFAULT("default"),
    MYSQL("mysql"),
    MSSQL("mssql"),
    ORACLE("oracle");

    private final String folder;

    SqlDialect(String folder) {
        this.folder = folder;
    }

    public static SqlDialect detect(JdbcTemplate jdbc) {
        String product = jdbc.execute((ConnectionCallback<String>) c ->
                c.getMetaData().getDatabaseProductName());
        String p = product == null ? "" : product.toLowerCase();
        if (p.contains("mysql") || p.contains("maria")) return MYSQL;
        if (p.contains("sql server"))                    return MSSQL;
        if (p.contains("oracle"))                        return ORACLE;
        return DEFAULT;
    }

    public String load(String basePath, String name) {
        InputStream is = resource(basePath + "/" + folder + "/" + name);
        if (is == null && this != DEFAULT) {
            is = resource(basePath + "/" + DEFAULT.folder + "/" + name);
        }
        if (is == null) {
            throw new IllegalStateException(
                    "SQL file not found: " + name + " (dialect=" + folder + ", base=" + basePath + ")");
        }
        try (InputStream in = is) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SQL: " + name, e);
        }
    }

    private static InputStream resource(String path) {
        return SqlDialect.class.getResourceAsStream(path);
    }
}
