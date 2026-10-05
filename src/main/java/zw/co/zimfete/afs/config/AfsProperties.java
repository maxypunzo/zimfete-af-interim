package zw.co.zimfete.afs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "afs")
public record AfsProperties(Login login, String officerName) {
    public record Login(String username, String password) {
    }
}
