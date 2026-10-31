import com.infinifolder.archive.*;
import io.github.gretchycat.infiniscribe.*;
import io.github.gretchycat.infinistream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Actual APK classes and Android SAX runtime; chapter WebView presentation needs handset acceptance. */
public class EpubRuntimeCheck {
    static File fixture, work;
    static CancellationToken none=()->false;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static void pass(String name) { System.out.println("PASS epub."+name); }
    static File expand(String name) throws Exception {
        File directory=Files.createTempDirectory(work.toPath(),"book-").toFile();
        ZipExtractionReport report=VaultZipExtraction.INSTANCE.extract(new File(fixture,name),directory,file->{
            try { return new FileOutputStream(file); } catch(IOException error) { throw new UncheckedIOException(error); }
        },none);
        check(report.getFiles()>0 && report.getSkippedUnsafeEntries()==0,"ZIP resource expansion");
        return directory;
    }
    static DirectoryDocumentResources resources(File directory) { return new DirectoryDocumentResources(directory,new FileSourceResource(new File(fixture,"sample-book.epub"),new ContentType()).getMetadata()); }
    static String read(SourceResource resource) throws Exception {
        try(ByteSource source=resource.open()) {
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] b=new byte[4096];int count;
            while((count=source.read(b,0,b.length))!=-1) output.write(b,0,count);
            return output.toString("UTF-8");
        }
    }
    public static void main(String[] args) {
        try {
            fixture=new File(args[0]);work=Files.createTempDirectory(new File(args[1]).toPath(),"epub-runtime-").toFile();
            File main=expand("sample-book.epub");
            DirectoryDocumentResources input=resources(main);
            try(EpubBook book=EpubDocuments.read(input)) {
                check(book.getTitle().equals("Café — EPUB migration") && book.getAuthor().equals("Synthetic Author"),"Unicode metadata");
                check(book.getChapters().size()==2,"linear spine");
                check(book.getChapters().get(0).getTitle().equals("First chapter (NCX)"),"NCX first title");
                check(book.getChapters().get(1).getPath().equals("OEBPS/text/second chapter.xhtml"),"encoded path");
                check(book.getMetadata().getContent().getKind()==DataKind.STRUCTURED,"book output kind");
                check(book.getMetadata().getAttributes().get("chapterCount").equals("2"),"actual chapter count");
                check(book.getMetadata().getLength()==null && book.getMetadata().getCapabilities().isEmpty(),"no encoded capabilities on book output");
                check(book.getMetadata().getProvenance().getSource().equals(input.getMetadata().getProvenance().getSource()),"original provenance");
            }
            pass("epub2-metadata-spine-ncx");
            try(EpubBook book=EpubDocuments.read(resources(expand("sample-fallback-book.epub")))) {
                check(book.getTitle().equals("EPUB Reader") && book.getAuthor().isEmpty(),"metadata fallback");
                check(book.getChapters().size()==2 && book.getChapters().get(0).getTitle().equals("Heading fallback") && book.getChapters().get(1).getTitle().equals("Chapter 3"),"manifest and HTML fallback");
            }
            pass("package-manifest-title-fallback");
            try(EpubBook book=EpubDocuments.read(resources(expand("sample-epub3.epub")))) {
                check(book.getChapters().size()==2,"EPUB3 spine");
                check(book.getChapters().get(0).getTitle().equals("EPUB 3 first title") && book.getChapters().get(1).getTitle().equals("EPUB 3 second heading"),"existing EPUB3 HTML-title fallback");
            }
            pass("epub3-spine-html-titles");
            check(read(input.source("OEBPS/styles/reader.css")).contains("border"),"related CSS");
            check(read(input.source("OEBPS/images/shapes.svg")).contains("circle"),"related image");
            check(input.source("../outside")==null,"confined resource lookup");
            SourceResource plus=input.source("OEBPS/text/appendix+notes.xhtml");
            try(ByteSource a=plus.open();ByteSource b=plus.open()) {
                check(a instanceof PositionalReadable,"actual positional source");
                byte[] x=new byte[16],y=new byte[16];
                check(((PositionalReadable)a).readAt(10,x,0,16)==16,"positional read");
                b.read(y,0,16);a.read(x,0,16);check(Arrays.equals(x,y),"independent cursors");
            }
            input.close();check(main.isDirectory(),"borrowed directory lifetime");
            pass("related-resources-positional-ownership");
            boolean invalid=false;
            try(EpubBook ignored=EpubDocuments.read(resources(expand("invalid-book.epub")))) {} catch(Exception expected) { invalid=true; }
            check(invalid,"invalid package must fail");pass("invalid-package");
            File altered=expand("sample-book.epub");File opf=new File(altered,"OEBPS/content.opf");
            for(String declaration:new String[]{"<!DOCTYPE package [<!ENTITY x 'expanded'>]>","<!DOCTYPE package SYSTEM 'file:///missing.dtd' [<!ENTITY x 'expanded'>]>"}) {
                Files.write(opf.toPath(),(declaration+"<package><metadata><title>&x;</title></metadata></package>").getBytes(StandardCharsets.UTF_8));
                boolean entities=false;
                try(EpubBook ignored=EpubDocuments.read(resources(altered))) {} catch(Exception expected) { entities=true; }
                check(entities,"custom internal entities rejected on Android");
            }
            pass("xml-internal-entities");
            Files.write(opf.toPath(),"<!DOCTYPE package SYSTEM 'file:///definitely-not-readable/infinistream-test.dtd'><package><metadata><title>Local title</title></metadata></package>".getBytes(StandardCharsets.UTF_8));
            try(EpubBook book=EpubDocuments.read(resources(altered))) { check(book.getTitle().equals("Local title"),"external DTD must not be loaded"); }
            pass("xml-external-dtd-offline");
            boolean limited=false;
            try(EpubBook ignored=EpubDocuments.read(resources(altered),"EPUB Reader",none,new EpubReadLimits(32,Long.MAX_VALUE,Long.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE))) {} catch(Exception expected) { limited=true; }
            check(limited,"configured metadata byte budget");pass("xml-budget");
            boolean cancelled=false;
            try(EpubBook ignored=EpubDocuments.read(input,"EPUB Reader",()->true)) {} catch(InterruptedIOException expected) { cancelled=true; }
            check(cancelled,"common cancellation propagates");pass("cancellation");
            File nested=new File(work,"nested.epub");
            try(ZipFile outer=new ZipFile(new File(fixture,"epub-checks.zip"));InputStream stream=outer.getInputStream(outer.getEntry("documents/sample-book.epub"))) { Files.copy(stream,nested.toPath()); }
            File nestedRoot=new File(work,"nested-resources");nestedRoot.mkdirs();
            VaultZipExtraction.INSTANCE.extract(nested,nestedRoot,file->{
                try { return new FileOutputStream(file); } catch(IOException error) { throw new UncheckedIOException(error); }
            },none);
            try(EpubBook book=EpubDocuments.read(resources(nestedRoot))) { check(book.getChapters().size()==2,"nested archive book"); }
            pass("nested-zip-package");
            delete(work);System.exit(0);
        } catch(Throwable error) { error.printStackTrace();if(work!=null)delete(work);System.exit(1); }
    }
    static void delete(File file) { File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete(); }
}
