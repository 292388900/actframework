package testapp.endpoint;

import act.cli.Command;
import act.controller.Controller;
import act.data.annotation.ReadContent;
import act.util.AnnotatedClassFinder;
import org.osgl.inject.annotation.AnnotatedWith;
import org.osgl.mvc.annotation.PostAction;
import org.osgl.storage.impl.SObject;
import org.osgl.util.C;
import org.osgl.util.IO;

import javax.inject.Named;
import java.io.File;
import java.util.List;

@Controller("/misc")
@SuppressWarnings("unused")
public class MiscsTestBed extends Controller.Util {

    @PostAction("readRaw")
    public String testReadContentRaw(File content) {
        return IO.readContentAsString(content);
    }

    @PostAction("read")
    @Command("misc.cat")
    public String testReadContentMercy(
            @ReadContent(mercy = true) String content
    ) {
        return content;
    }

    @PostAction("readLine")
    @Command("misc.catLine")
    public List<String> testReadLinesMercy(
            @ReadContent(mercy = true) List<String> lines
    ) {
        return lines;
    }

    @PostAction("hardRead")
    @Command("misc.cat.hard")
    public String testReadContent(
            @ReadContent String content
    ) {
        return content;
    }

    @PostAction("hardReadLine")
    @Command("misc.catLine.hard")
    public List<String> testReadLines(
            @ReadContent List<String> lines
    ) {
        return lines;
    }

    @AnnotatedClassFinder(Controller.class)
    public static void findXxxClass(Class<?> c) {
        System.out.printf(">>>>>> %s\n", c.getName());
    }


    @PostAction("readMulti")
    public Object testMultiFileRead(
            @ReadContent(mercy = true) String file1,
            File file2,
            SObject file3
    ) {
        String c1 = file1;
        String c2 = IO.readContentAsString(file2);
        String c3 = IO.readContentAsString(file3.asInputStream());
        return render(c1, c2, c3);
    }


    @PostAction("uploadMulti")
    public Object testMultiUpload(List<File> files) {
        return C.list(files).map(IO::readContentAsString);
    }
}
