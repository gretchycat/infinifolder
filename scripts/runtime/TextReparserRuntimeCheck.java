import io.github.gretchycat.infiniscribe.reparse.JsonTextReparser;
import io.github.gretchycat.infiniscribe.reparse.TextReparsing;
import io.github.gretchycat.infiniscribe.TextDocument;
import io.github.gretchycat.infiniscribe.TextDocuments;
import io.github.gretchycat.infinistream.InputStreamSource;
import io.github.gretchycat.infinistream.StreamMetadata;
import io.github.gretchycat.infiniscribe.reparse.XmlTextReparser;
import io.github.gretchycat.infiniscribe.reparse.YamlTextReparser;
import io.github.gretchycat.infiniscribe.reparse.TomlTextReparser;
import org.tomlj.Toml;
import org.yaml.snakeyaml.Yaml;
import io.github.gretchycat.infinistream.CancellationToken;

public final class TextReparserRuntimeCheck {
    private static String format(String input) {
        byte[] bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (InputStreamSource source = new InputStreamSource(new java.io.ByteArrayInputStream(bytes),
                new StreamMetadata(), true, () -> false)) {
            TextDocument original = TextDocuments.INSTANCE.read(source, bytes.length, java.nio.charset.StandardCharsets.UTF_8, () -> false);
            TextDocument display = TextReparsing.INSTANCE.reparse(original, JsonTextReparser.INSTANCE, () -> false);
            check(display.getMetadata().getLength() == null && display.getMetadata().getCapabilities().isEmpty(), "Stale source metadata retained");
            check("reparsed".equals(display.getMetadata().getAttributes().get("representation")), "Missing actual output representation");
            check(java.util.Arrays.equals(bytes, original.getBytes()), "Original bytes mutated");
            return display.getText();
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static boolean sameToml(Object before, Object after) {
        if (before instanceof org.tomlj.TomlTable && after instanceof org.tomlj.TomlTable) {
            org.tomlj.TomlTable a = (org.tomlj.TomlTable) before, b = (org.tomlj.TomlTable) after;
            if (!a.keySet().equals(b.keySet())) return false;
            for (String key : a.keySet()) {
                java.util.List<String> path = java.util.Collections.singletonList(key);
                if (!sameToml(a.get(path), b.get(path))) return false;
            }
            return true;
        }
        if (before instanceof org.tomlj.TomlArray && after instanceof org.tomlj.TomlArray) {
            org.tomlj.TomlArray a = (org.tomlj.TomlArray) before, b = (org.tomlj.TomlArray) after;
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameToml(a.get(i), b.get(i))) return false;
            return true;
        }
        return java.util.Objects.equals(before, after);
    }
    private static void otherFormats() {
        System.out.println("Checking XML");
        String xml = "<root xmlns:p=\"urn:test\"><p:item value=\"a&#10;b\"/><p>Hello <b>world</b> !</p><c><![CDATA[a<b]]></c><space xml:space=\"preserve\">  <a/>  </space><!--keep--></root>";
        String xmlOut = XmlTextReparser.INSTANCE.format(xml, () -> false);
        check(xmlOut.contains("\n  <p:item"), "XML not indented");
        check(xmlOut.contains("<p>Hello <b>world</b> !</p>"), "XML mixed content changed");
        check(xmlOut.contains("<![CDATA[a<b]]>"), "XML CDATA lost");
        check(xmlOut.contains("value=\"a&#10;b\""), "XML attribute whitespace changed");
        check(xmlOut.contains("  <a/>  </space>"), "XML preserve space changed");
        check(xmlOut.contains("xmlns:p=\"urn:test\"") && xmlOut.contains("<!--keep-->"), "XML namespace/comment lost");
        check(XmlTextReparser.INSTANCE.format(xmlOut, () -> false).equals(xmlOut), "XML not stable");
        for (String bad : new String[]{"<a><b></a>", "<!DOCTYPE a SYSTEM 'file:///not-read'><a/>"}) {
            boolean rejected = false;
            try { XmlTextReparser.INSTANCE.format(bad, () -> false); } catch (Exception expected) { rejected = true; }
            check(rejected, "Bad XML accepted");
        }
        System.out.println("Checking YAML");
        String yaml = "# keep comment\nfirst: &item {name: 'yes', value: 1.2300}\nsecond: *item\nlist: [one, two]\n---\nlast: value\n";
        String yamlOut = YamlTextReparser.INSTANCE.format(yaml, () -> false);
        check(yamlOut.contains("# keep comment") && yamlOut.contains("'yes'") && yamlOut.contains("1.2300"), "YAML comment/scalar spelling lost");
        check(yamlOut.contains("  name:") && yamlOut.contains("*"), "YAML flow not expanded or alias lost");
        java.util.Iterator<Object> before = new Yaml().loadAll(yaml).iterator();
        java.util.Iterator<Object> after = new Yaml().loadAll(yamlOut).iterator();
        while (before.hasNext()) { check(after.hasNext() && before.next().equals(after.next()), "YAML data changed"); }
        check(!after.hasNext(), "Extra YAML document");
        System.out.println("Checking TOML");
        String toml = "title = 'example'\n[server]\nport=8080\ndate=1979-05-27T07:32:00Z\nitems=[1,2,3]\ninline={a=1,b='two'}\n";
        String tomlOut = TomlTextReparser.INSTANCE.format(toml, () -> false);
        check(!Toml.parse(tomlOut).hasErrors(), "TOML output invalid");
        check(sameToml(Toml.parse(toml), Toml.parse(tomlOut)), "TOML data changed");
        boolean rejected = false;
        try { TomlTextReparser.INSTANCE.format("x = [", () -> false); } catch (Exception expected) { rejected = true; }
        check(rejected, "Bad TOML accepted");
        rejected = false;
        try { YamlTextReparser.INSTANCE.format("x: [", () -> false); } catch (Exception expected) { rejected = true; }
        check(rejected, "Bad YAML accepted");
        System.out.println("PASS: XML mixed content, namespace, CDATA, whitespace, comments, invalid input");
        System.out.println("PASS: YAML comments, scalar spelling, aliases, multiple documents, semantic round trip");
        System.out.println("PASS: TOML tables, arrays, inline tables, timestamps, semantic round trip, invalid input");
    }
    private static String compact(String input) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false, escaped = false;
        for (char c : input.toCharArray()) {
            if (quoted || !Character.isWhitespace(c)) out.append(c);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
        }
        return out.toString();
    }
    private static void actualFile(String path) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
        String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        long start = System.nanoTime();
        String output = format(source);
        long formatMs = (System.nanoTime() - start) / 1000000;
        check(compact(source).equals(compact(output)), "Real document tokens changed");
        check(format(output).equals(output), "Real document reformat unstable");
        System.out.println("PASS: actual file tokens unchanged, bytes=" + bytes.length + " reparseMs=" + formatMs);
        long logicalLines = output.chars().filter(c -> c == '\n').count() + 1;
        check(java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))), "Source file modified");
        System.out.println("PASS: actual file bytes=" + bytes.length + " formattedChars=" + output.length()
            + " logicalLines=" + logicalLines + " reparseMs=" + formatMs + " tokens unchanged, source unchanged");
    }
    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        System.out.println("Checking JSON");
        for (String scalar : new String[]{"null", "true", "123.00e-2", "\"text\""}) {
            check(format(scalar).equals(scalar), "Scalar JSON root rejected or changed");
        }
        check(format("\uFEFF{}").equals("{}"), "JSON BOM not handled");
        String source = "{\"number\":1.2300e+09,\"duplicate\":1,\"duplicate\":2,\"string\":\"a,{}[]:\\\"b\\\\c\",\"empty\":[{},[]]}";
        String output = format(source);
        check(output.contains("\"number\": 1.2300e+09"), "Number spelling changed");
        check(output.contains("\"duplicate\": 1") && output.contains("\"duplicate\": 2"), "Duplicate keys lost");
        check(output.contains("\"a,{}[]:\\\"b\\\\c\""), "String changed");
        check(output.contains("{}") && output.contains("[]"), "Empty containers expanded");
        check(format(output).equals(output), "Formatting is not stable");
        for (String invalid : new String[]{"{broken}", "{\"x\":}", "{} {}", "[1,]", "{\"x\":NaN}"}) {
            boolean rejected = false;
            try { format(invalid); } catch (Exception expected) { rejected = true; }
            check(rejected, "Accepted invalid JSON: " + invalid);
        }
        StringBuilder large = new StringBuilder("[");
        for (int i = 0; i < 26000; i++) {
            if (i > 0) large.append(',');
            large.append("{\"id\":").append(i).append(",\"text\":\"hello world\"}");
        }
        large.append(']');
        long start = System.nanoTime();
        String formatted = format(large.toString());
        check(formatted.contains("\"id\": 25999"), "Large document truncated");
        check(format(formatted).equals(formatted), "Large document unstable");
        check(!large.toString().contains("\n"), "Original buffer changed");
        boolean cancelled = false;
        try { JsonTextReparser.INSTANCE.format("{}", () -> true); }
        catch (Exception expected) { cancelled = true; }
        check(cancelled, "Cancellation ignored");
        long syntheticMs = (System.nanoTime() - start) / 1000000;
        otherFormats();
        if (args.length > 0) actualFile(args[0]);
        System.out.println("PASS: strings, numbers, duplicate keys, invalid input, idempotence, cancellation");
        System.out.println("PASS: single-line JSON bytes=" + large.length() + " formattedChars=" + formatted.length()
            + " formatAndReformatMs=" + syntheticMs);
    }
}
