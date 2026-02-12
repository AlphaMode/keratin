package net.ornithemc.keratin.api.task.unpick;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import daomephsta.unpick.api.ConstantUninliner;
import daomephsta.unpick.api.classresolvers.ClassResolvers;
import daomephsta.unpick.api.classresolvers.IClassResolver;
import daomephsta.unpick.api.constantgroupers.ConstantGroupers;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public interface Unpick {

	interface UnpickParameters extends WorkParameters {

		Property<File> getInputJar();

		Property<File> getUnpickDefinitionsFile();

		Property<File> getUnpickConstantsJar();

		SetProperty<File> getUnpickClasspath();

		Property<File> getOutputJar();

	}

	abstract class UnpickMinecraft implements WorkAction<UnpickParameters>, Unpick {

		@Override
		public void execute() {
			File input = getParameters().getInputJar().get();
			File unpickDefinitions = getParameters().getUnpickDefinitionsFile().get();
			File unpickConstants = getParameters().getUnpickConstantsJar().get();
			Set<File> unpickClasspath = getParameters().getUnpickClasspath().get();
			File output = getParameters().getOutputJar().get();

			try {
				unpickJar(input, output, unpickDefinitions, unpickConstants, unpickClasspath);
			} catch (IOException e) {
				throw new RuntimeException("error while unpicking Minecraft", e);
			}
		}
	}

	default void unpickJar(File input, File output, File unpickDefinitions, File unpickConstants, Collection<File> unpickClasspath) throws IOException {
		_unpickJar(input, output, unpickDefinitions, unpickConstants, unpickClasspath);
	}

	static void _unpickJar(File input, File output, File unpickDefinitions, File unpickConstants, Collection<File> unpickClasspath) throws IOException {
        List<ZipFile> classpathZips = new ArrayList<>();

        try (
                ZipFile inputZip = new ZipFile(input);
                Reader mappingsReader = new BufferedReader(new FileReader(unpickDefinitions));
                ZipOutputStream outputZip = new ZipOutputStream(new FileOutputStream(output))
        ) {
            IClassResolver classResolver = ClassResolvers.jar(inputZip);

            ZipFile constants = new ZipFile(unpickConstants);
            classpathZips.add(constants);
            classResolver = classResolver.chain(ClassResolvers.jar(constants));

            for (File file : unpickClasspath) {
                ZipFile zip = new ZipFile(file);
                classpathZips.add(zip);
                classResolver = classResolver.chain(ClassResolvers.jar(zip));
            }

            classResolver = classResolver.chain(ClassResolvers.classpath());

            ConstantUninliner uninliner = ConstantUninliner.builder()
                    .classResolver(classResolver)
                    .grouper(ConstantGroupers.dataDriven()
                            .classResolver(classResolver)
                            .mappingSource(mappingsReader)
                            .build())
                    .build();

            try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
                List<CompletableFuture<PendingOutputEntry>> entryFutures = new ArrayList<>();
                Enumeration<? extends ZipEntry> inputEntries = inputZip.entries();

                while (inputEntries.hasMoreElements()) {
                    ZipEntry entry = inputEntries.nextElement();
                    entryFutures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            if (entry.isDirectory()) {
                                return new PendingOutputEntry(entry.getName(), null);
                            } else if (!entry.getName().endsWith(".class")) {
                                return new PendingOutputEntry(entry.getName(), inputZip.getInputStream(entry).readAllBytes());
                            } else {
                                ClassNode clazz = new ClassNode();
                                new ClassReader(inputZip.getInputStream(entry)).accept(clazz, 0);
                                uninliner.transform(clazz);
                                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                                clazz.accept(writer);
                                return new PendingOutputEntry(entry.getName(), writer.toByteArray());
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }, executor));
                }

                for (CompletableFuture<PendingOutputEntry> entryFuture : entryFutures) {
                    PendingOutputEntry entry = entryFuture.join();
                    outputZip.putNextEntry(new ZipEntry(entry.name));

                    if (entry.data != null) {
                        outputZip.write(entry.data);
                    }

                    outputZip.closeEntry();
                }
            }
        } finally {
            for (ZipFile classpathZip : classpathZips) {
                try {
                    classpathZip.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
	}

    record PendingOutputEntry(String name, byte[] data) {
    }
}
