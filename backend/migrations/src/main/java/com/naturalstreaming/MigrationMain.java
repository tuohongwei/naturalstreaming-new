package com.naturalstreaming;
import org.flywaydb.core.Flyway;
public class MigrationMain {public static void main(String[] args){String url=System.getenv("DB_URL");if(url==null||!url.matches("jdbc:mysql://127[.]0[.]0[.]1:3306/naturalstreaming(?:[?].*)?"))throw new IllegalArgumentException("Isolated NaturalStreaming schema required");Flyway.configure().dataSource(url,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).load().migrate();}}
