package com.tonepilot.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDataTablePersistenceArchitectureTest {

    private static final Path BACKEND_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void 数据管理不能在服务层直接使用JdbcTemplate而应通过Repository和MyBatisXml访问数据库() throws Exception {
        Path serviceFile = BACKEND_ROOT.resolve("infrastructure/src/main/java/com/tonepilot/infrastructure/admin/data/AdminDataTableService.java");
        String serviceCode = Files.readString(serviceFile);

        assertThat(serviceCode).doesNotContain("JdbcTemplate");
        assertThat(BACKEND_ROOT.resolve("repository/src/main/java/com/tonepilot/repository/admin/data/AdminDataTableRepository.java"))
                .exists();
        Path mapperXml = BACKEND_ROOT.resolve("infrastructure/src/main/resources/mapper/admin/AdminDataTableMapper.xml");
        assertThat(mapperXml).exists();
        assertThat(Files.readString(mapperXml)).contains("<mapper");
    }
}
