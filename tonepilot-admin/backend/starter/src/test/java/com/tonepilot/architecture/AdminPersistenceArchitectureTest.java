package com.tonepilot.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPersistenceArchitectureTest {

    private static final Path BACKEND_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Pattern SQL_ANNOTATION = Pattern.compile("@(Select|Insert|Update|Delete)\\s*\\(");

    @Test
    void 生产代码禁止直接使用JdbcTemplate并禁止使用MyBatisSql注解() throws Exception {
        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(BACKEND_ROOT)) {
            javaFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        }

        List<String> violations = javaFiles.stream()
                .filter(path -> {
                    try {
                        String code = Files.readString(path);
                        return code.contains("JdbcTemplate")
                                || code.contains("NamedParameterJdbcTemplate")
                                || SQL_ANNOTATION.matcher(code).find();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .map(path -> BACKEND_ROOT.relativize(path).toString())
                .sorted()
                .toList();

        assertThat(violations)
                .as("数据库访问必须经过 repository 端口和 MyBatis XML，生产代码不能直接使用 JdbcTemplate 或 SQL 注解")
                .isEmpty();
    }
}
