package com.syntren.sypass.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvParserTest {

    @Test
    @DisplayName("Parse simple comma-separated line")
    void testSimpleLine() {
        List<String> cols = PasswordManager.parseCsvLine("server.net,player1,superSecret123,/login");
        assertEquals(4, cols.size());
        assertEquals("server.net", cols.get(0));
        assertEquals("player1", cols.get(1));
        assertEquals("superSecret123", cols.get(2));
        assertEquals("/login", cols.get(3));
    }

    @Test
    @DisplayName("Parse line with quoted commas")
    void testQuotedCommas() {
        List<String> cols = PasswordManager.parseCsvLine("\"mc.server.com, port 25565\",\"user,with,comma\",\"p@ss,w0rd\",\"/login\"");
        assertEquals(4, cols.size());
        assertEquals("mc.server.com, port 25565", cols.get(0));
        assertEquals("user,with,comma", cols.get(1));
        assertEquals("p@ss,w0rd", cols.get(2));
        assertEquals("/login", cols.get(3));
    }

    @Test
    @DisplayName("Parse line with escaped double quotes")
    void testEscapedQuotes() {
        List<String> cols = PasswordManager.parseCsvLine("\"Server \"\"Hypixel\"\"\",Steve,\"p\"\"ass\"");
        assertEquals(3, cols.size());
        assertEquals("Server \"Hypixel\"", cols.get(0));
        assertEquals("Steve", cols.get(1));
        assertEquals("p\"ass", cols.get(2));
    }

    @Test
    @DisplayName("Parse empty and null line gracefully")
    void testEmptyLine() {
        assertTrue(PasswordManager.parseCsvLine("").isEmpty());
        assertTrue(PasswordManager.parseCsvLine(null).isEmpty());
    }
}
