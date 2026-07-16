package com.changy.tailoragent.Document.config;

import com.changy.tailoragent.web.AppPaths;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * SQLite + MyBatis 手动配置。
 *
 * <p>MyBatis starter 自动配置与 Spring Boot 4.x 不兼容（PropertyMapper API 变更），
 * 因此手动创建 SqlSessionFactory + MapperScannerConfigurer，不依赖 MybatisAutoConfiguration。
 *
 * <p>编程式创建 DataSource（路径由 {@link AppPaths#dataDir()} 决定，无法写在
 * application.yml 中）。同时初始化 schema 并扫描 mapper 包。
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);
    private static final String DATABASE_FILE_NAME = "tailor_agent.db";

    @Bean
    public DataSource dataSource() {
        Path dbDir = AppPaths.dataDir().resolve("data");
        if (!dbDir.toFile().mkdirs() && !dbDir.toFile().isDirectory()) {
            throw new IllegalStateException("无法创建数据库目录: " + dbDir);
        }
        Path dbFile = dbDir.resolve(DATABASE_FILE_NAME);

        SQLiteConfig config = new SQLiteConfig();
        // WAL 模式：写操作不阻塞读，桌面单用户场景足够
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);

        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return ds;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        // XML mapper 位置
        factory.setMapperLocations(
            new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/**/*.xml"));

        // map-underscore-to-camel-case
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);

        return factory.getObject();
    }

    /**
     * 静态 Bean —— 提前扫描 mapper 接口，确保在 Service 注入之前注册。
     * 必须 static，否则 @Configuration 代理会导致提前初始化问题。
     *
     * <p>扫描整个根包，但通过 {@code annotationClass = @Mapper} 限定：只注册带
     * {@link org.apache.ibatis.annotations.Mapper @Mapper} 注解的接口。这样既能覆盖
     * Document 与 chat 等多个模块的 Mapper，又不会误扫到同名为 "mapper" 实为
     * 转换器的 {@code ChatMessageMapper}（它只标了 {@code @Component}）。
     */
    @Bean
    public static MapperScannerConfigurer mapperScannerConfigurer() {
        MapperScannerConfigurer scanner = new MapperScannerConfigurer();
        scanner.setBasePackage("com.changy.tailoragent");
        scanner.setAnnotationClass(org.apache.ibatis.annotations.Mapper.class);
        scanner.setSqlSessionFactoryBeanName("sqlSessionFactory");
        return scanner;
    }

    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 应用启动后执行建表（幂等：CREATE TABLE IF NOT EXISTS）。
     * 不依赖 spring.sql.init，避免 Init 先于 DataSource Bean 创建。
     */
    @Bean
    @Order(0) // 必须先于 SessionService 的启动恢复(@Order(1))建表
    ApplicationRunner initSchema(DataSource dataSource) {
        return args -> {
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                // 知识库目录索引 —— 正文以真实文件存于 dataDir()/knowledge/{MD,files}，
                // 此表只存"指向文件"的元数据，主键为相对 knowledge 根的相对路径（形如 MD/工作/报告.md）。
                // status: unindexed(默认/脏) | processing | indexed | failed
                // content_hash / chunked_hash: 惰性于索引时计算，用于判断是否需重新切块（不等或 null 即需重切）
                // language=SQLite
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS kb_document (
                        rel_path      TEXT PRIMARY KEY,
                        status        TEXT NOT NULL DEFAULT 'unindexed'
                                           CHECK (status IN ('unindexed','processing','indexed','failed')),
                        content_hash  TEXT,
                        chunked_hash  TEXT,
                        indexed_at    TEXT,
                        tags          TEXT
                    )
                    """);
                log.info("数据库表 kb_document 初始化完成");

                // 会话持久化：会话 → 轮次 → 事件 三层
                // language=SQLite
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_session (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        title       TEXT    NOT NULL DEFAULT '新会话',
                        created_at  TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
                        updated_at  TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )
                    """);
                // 轮次：一次用户输入 + 智能体的全部响应活动。
                // model / usage_json 为预留冗余字段，当前不填充。
                // language=SQLite
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_turn (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id  INTEGER NOT NULL,
                        seq         INTEGER,
                        kind        TEXT    NOT NULL DEFAULT 'qa',
                        status      TEXT    NOT NULL DEFAULT 'running',
                        model       TEXT,
                        usage_json  TEXT,
                        created_at  TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )
                    """);
                // 事件/消息：轮次内的原子单元，异构。
                // 顺序唯一由自增 id 决定；turn_id / seq 为冗余字段，不参与排序。
                // type: text / tool_call / tool_result（文件编辑暂折叠进 tool_result 的 payload）
                // language=SQLite
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_event (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        turn_id     INTEGER NOT NULL,
                        session_id  INTEGER NOT NULL,
                        seq         INTEGER,
                        role        TEXT    NOT NULL,
                        type        TEXT    NOT NULL DEFAULT 'text',
                        content     TEXT,
                        payload     TEXT,
                        status      TEXT,
                        created_at  TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )
                    """);
                // 整会话加载走 (session_id, id)；启动恢复走 status
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_event_session ON chat_event(session_id, id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_turn_session ON chat_turn(session_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_turn_status ON chat_turn(status)");
                log.info("数据库表 chat_session / chat_turn / chat_event 初始化完成");
            }
        };
    }
}
