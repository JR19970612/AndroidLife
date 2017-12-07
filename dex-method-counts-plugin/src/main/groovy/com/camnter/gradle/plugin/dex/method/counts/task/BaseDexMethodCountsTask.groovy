package com.camnter.gradle.plugin.dex.method.counts.task

import com.android.build.gradle.api.BaseVariantOutput
import com.android.dexdeps.DexData
import com.camnter.gradle.plugin.dex.method.counts.DexCount
import com.camnter.gradle.plugin.dex.method.counts.DexFieldCounts
import com.camnter.gradle.plugin.dex.method.counts.DexMethodCounts
import com.camnter.gradle.plugin.dex.method.counts.struct.Filter
import com.camnter.gradle.plugin.dex.method.counts.struct.OutputStyle
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

abstract class BaseDexMethodCountsTask extends DefaultTask {

    @Input
    @Optional
    File fileToCount

    @Input
    @Optional
    BaseVariantOutput variantOutput

    boolean countFields = true
    boolean includeClasses = true
    String packageFilter = ""
    int maxDepth = Integer.MAX_VALUE
    Filter filter = Filter.ALL
    OutputStyle outputStyle = OutputStyle.TREE

    StringBuilder stringBuilder

    @TaskAction
    void main() {
        if (fileToCount == null || !fileToCount.exists()) return
        stringBuilder = new StringBuilder()
        try {
            // TODO countFields
            // TODO includeClasses
            // TODO packageFilter

            for (String fileName : collectFileNames(fileToCount)) {
                stringBuilder
                        .append(" Processing ${fileToCount}")
                        .append("\n")
                recordOutputBasicInformation()

                DexCount counts
                if (countFields) {
                    counts = new DexFieldCounts(outputStyle)
                } else {
                    counts = new DexMethodCounts(outputStyle)
                }
                // 路径文件（ dex or apk ），文件内的每个 （ dex ）
                List<RandomAccessFile> dexFiles = openInputFiles(fileName)

                /**
                 * 遍历每个 dex 数据
                 * 通过 google 的 api，实例化 DexData
                 * 通过 google 的 api，生成 所有 dex 数据（ class，package，maxDepth and filter ）
                 * */
                for (RandomAccessFile dexFile : dexFiles) {
                    DexData dexData = new DexData(dexFile)
                    dexData.load()
                    // 计算方法数
                    counts.generate(dexData, includeClasses, packageFilter, maxDepth, filter)
                    dexFile.close()
                }
                // 输出方法数信息
                counts.output()
                // 获取方法数
                int overallCount = counts.getOverallCount()
                stringBuilder
                        .append(String.format("Overall %s count: %d",
                        countFields ? "field" : "method",
                        overallCount))
                        .append("\n")
            }
        } catch (UsageException ignored) {
            stringBuilder
                    .append(usage())
                    .append("\n")
        }

        // TODO 打印文件
    }

    def recordOutputBasicInformation() {
        if (variantOutput == null) return
        record("%-29s = %s\n", "[name]", $ { variantOutput.name })
        record("%-29s = %s\n", "[dirName]", $ { variantOutput.dirName })
        record("%-29s = %s\n", "[baseName]", $ { variantOutput.baseName })
        record("%-29s = %s\n", "[assemble]", $ { variantOutput.assemble })
        record("%-29s = %s\n", "[outputFile]", $ { variantOutput.outputFile })
        record("%-29s = %s\n", "[outputType]", $ { variantOutput.outputType })
        record("%-29s = %s\n", "[versionCode]", $ { variantOutput.versionCode })
        record("%-29s = %s\n", "[processResources]", $ { variantOutput.processResources })
        record("%-29s = %s\n", "[outputFile.exists]", $ { variantOutput.outputFile.exists() })
        record("%-29s = %s\n", "[processResources.name]", $ { variantOutput.processResources.name })
        record("%-29s = %s\n", "[processResources.class.name]",
                $ { variantOutput.processResources.class.name })
    }

    def record(def format, def previousValue, def nextValue) {
        stringBuilder.append(String.format(format, previousValue, nextValue))
    }

    /**
     * Opens an input file, which could be a .dex or a .jar/.apk with a
     * classes.dex inside.  If the latter, we extract the contents to a
     * temporary file.
     *
     * 将路径转为 zip
     * 获取 zip 文件中的每个 dex 的数据（  RandomAccessFile ）
     * 返回一个  List<RandomAccessFile>
     * */
    static List<RandomAccessFile> openInputFiles(String fileName) throws IOException {
        List<RandomAccessFile> dexFiles = new ArrayList<RandomAccessFile>()

        openInputFileAsZip(fileName, dexFiles)
        if (dexFiles.size() == 0) {
            File inputFile = new File(fileName)
            RandomAccessFile dexFile = new RandomAccessFile(inputFile, "r")
            dexFiles.add(dexFile)
        }

        return dexFiles
    }

    /**
     * Tries to open an input file as a Zip archive (jar/apk) with a
     * "classes.dex" inside.
     *
     * 将 路径 转换为 zip
     *
     * 遍历 zip 文件中的每个 dex
     * 通过 openDexFile 方法获取 zip 文件中的每个 dex 的数据（ RandomAccessFile ）
     * 保存到集合 dexFiles
     * */
    void openInputFileAsZip(String fileName, List<RandomAccessFile> dexFiles) throws IOException {
        ZipFile zipFile

        // Try it as a zip file.
        try {
            zipFile = new ZipFile(fileName)
        } catch (FileNotFoundException fnfe) {
            // not found, no point in retrying as non-zip.
            stringBuilder.append("Unable to open '" + fileName + "': " + fnfe.getMessage())
            throw fnfe
        } catch (ZipException ze) {
            // not a zip
            return
        }

        // Open and add all files matching "classes.*\.dex" in the zip file.
        for (ZipEntry entry : Collections.list(zipFile.entries())) {
            if (entry.getName().matches("classes.*\\.dex")) {
                dexFiles.add(openDexFile(zipFile, entry))
            }
        }

        zipFile.close()
    }

    /**
     * 读取 zip 中的 dex 数据
     *
     * 其实就是创建了一个 new dex
     * 然后用 RandomAccessFile 把 zip 中的 dex 的数据写到 new dex 上
     * 最后删除这个 new dex，因为数据已经读完到了 RandomAccessFile 上
     * 不需要这个 new dex
     *
     * 返回的是 RandomAccessFile，已经将 zip 的数据读完了
     *
     * @param zipFile zipFile
     * @param entry entry
     * @return RandomAccessFile
     * @throws IOException IOException
     */
    static RandomAccessFile openDexFile(ZipFile zipFile, ZipEntry entry) throws IOException {
        // We know it's a zip; see if there's anything useful inside.  A
        // failure here results in some type of IOException (of which
        // ZipException is a subclass).
        InputStream zis = zipFile.getInputStream(entry)

        // Create a temp file to hold the DEX data, open it, and delete it
        // to ensure it doesn't hang around if we fail.
        File tempFile = File.createTempFile("dexdeps", ".dex")
        RandomAccessFile dexFile = new RandomAccessFile(tempFile, "rw")
        tempFile.delete()

        // Copy all data from input stream to output file.
        byte[] copyBuf = new byte[32768]
        int actual

        while (true) {
            actual = zis.read(copyBuf)
            if (actual == -1) {
                break
            }

            dexFile.write(copyBuf, 0, actual)
        }

        dexFile.seek(0)

        return dexFile
    }

    /**
     * Checks if input files array contain directories and
     * adds it's contents to the file list if so.
     * Otherwise just adds a file to the list.
     *
     * @return a List of file names to process
     */
    private static List<String> collectFileNames(String inputFileName) {
        List<String> fileNames = new ArrayList<String>()
        File file = new File(inputFileName)
        if (file.isDirectory()) {
            String dirPath = file.getAbsolutePath()
            for (String fileInDir : file.list()) {
                fileNames.add(dirPath + File.separator + fileInDir)
            }
        } else {
            fileNames.add(inputFileName)
        }
        return fileNames
    }

    static String usage() {
        return "DEX per-package/class method counts v1.5\n" +
                "Usage: dex-method-counts [options] <file.{dex,apk,jar,directory}> ...\n" +
                "Options:\n" +
                "  --count-fields\n" +
                "  --include-classes\n" +
                "  --package-filter=com.foo.bar\n" +
                "  --max-depth=N\n" +
                "  --filter=ALL|DEFINED_ONLY|REFERENCED_ONLY\n" +
                "  --output-style=FLAT|TREE\n"
    }

    private static class UsageException extends RuntimeException {}
}

