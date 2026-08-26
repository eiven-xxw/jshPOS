package com.jingshanghui.pos.reporting.config;

import com.jingshanghui.pos.reporting.application.port.ReportArtifactStore;
import com.jingshanghui.pos.reporting.application.port.ReportDownloadTokenProtector;
import com.jingshanghui.pos.reporting.application.port.SalesPageCursorCodec;
import com.jingshanghui.pos.reporting.infrastructure.export.*;
import com.jingshanghui.pos.reporting.infrastructure.security.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Base64;

/** Gate 5D Reporting 独立模块入口；缺少外部安全配置时仅导出能力失败关闭。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.reporting")
@MapperScan("com.jingshanghui.pos.reporting.infrastructure.persistence.mapper")
public class ReportingAutoConfiguration {
    @Bean
    public ReportCsvEncoder reportCsvEncoder() {
        return new ReportCsvEncoder();
    }

    @Bean
    public ReportArtifactStore reportArtifactStore(Environment environment) {
        String root = environment.getProperty("JSH_REPORT_ARTIFACT_ROOT");
        return root == null || root.isBlank() ? new RejectingReportArtifactStore()
            : new FileSystemReportArtifactStore(Path.of(root));
    }

    @Bean
    public ReportDownloadTokenProtector reportDownloadTokenProtector(Environment environment) {
        String encoded = environment.getProperty("JSH_REPORT_DOWNLOAD_HMAC_KEY_B64");
        if (encoded == null || encoded.isBlank()) return new RejectingReportDownloadTokenProtector();
        try {
            return new HmacReportDownloadTokenProtector(Base64.getDecoder().decode(encoded));
        } catch (RuntimeException exception) {
            return new RejectingReportDownloadTokenProtector();
        }
    }

    @Bean
    public SalesPageCursorCodec salesPageCursorCodec(Environment environment) {
        String encoded = environment.getProperty("JSH_REPORT_CURSOR_HMAC_KEY_B64");
        if (encoded == null || encoded.isBlank()) return new RejectingSalesPageCursorCodec();
        try {
            return new HmacSalesPageCursorCodec(Base64.getDecoder().decode(encoded));
        } catch (RuntimeException exception) {
            return new RejectingSalesPageCursorCodec();
        }
    }
}
