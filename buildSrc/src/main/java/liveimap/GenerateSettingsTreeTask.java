package liveimap;

import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

public abstract class GenerateSettingsTreeTask extends DefaultTask {
    @InputFile
    public abstract RegularFileProperty getTreeFile();

    @InputFile
    public abstract RegularFileProperty getScriptFile();

    @InputFile
    public abstract RegularFileProperty getTemplateFile();

    /** Display names live here. The script reads this file; the task must rerun when it changes. */
    @InputFile
    public abstract RegularFileProperty getBindingFile();

    @OutputDirectory
    public abstract DirectoryProperty getKtOut();

    @Inject
    public abstract ExecOperations getExecOperations();

    @TaskAction
    public void generate() {
        getKtOut().get().getAsFile().mkdirs();
        StringBuilder output = new StringBuilder();
        try {
            runScript(output);
        } catch (RuntimeException failed) {
            String text = output.toString().trim();
            if (text.isEmpty()) throw failed;
            throw new GradleException(text, failed);
        }
        String text = output.toString().trim();
        if (!text.isEmpty()) {
            getLogger().lifecycle(text);
        }
    }

    private void runScript(StringBuilder output) {
        getExecOperations().exec(spec -> {
            spec.commandLine(
                "python3",
                getScriptFile().get().getAsFile().getAbsolutePath(),
                getTreeFile().get().getAsFile().getAbsolutePath(),
                getKtOut().get().getAsFile().getAbsolutePath()
            );
            spec.setStandardOutput(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                    output.append((char) b);
                }
                @Override
                public void write(byte[] b, int off, int len) {
                    output.append(new String(b, off, len, StandardCharsets.UTF_8));
                }
            });
            spec.setErrorOutput(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                    output.append((char) b);
                }
                @Override
                public void write(byte[] b, int off, int len) {
                    output.append(new String(b, off, len, StandardCharsets.UTF_8));
                }
            });
        });
    }
}
