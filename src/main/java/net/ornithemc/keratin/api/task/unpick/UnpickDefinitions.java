package net.ornithemc.keratin.api.task.unpick;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;

import daomephsta.unpick.constantmappers.datadriven.parser.MemberKey;
import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Reader;
import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Remapper;
import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Writer;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import daomephsta.unpick.constantmappers.datadriven.parser.v2.UnpickV2Reader;
import daomephsta.unpick.constantmappers.datadriven.parser.v2.UnpickV2Remapper;
import daomephsta.unpick.constantmappers.datadriven.parser.v2.UnpickV2Writer;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import net.ornithemc.keratin.KeratinGradleExtension;
import net.ornithemc.keratin.api.MinecraftVersion;
import net.ornithemc.keratin.util.Patterns;
import net.ornithemc.mappingutils.MappingUtils;

public interface UnpickDefinitions {

	String FILE_EXTENSION = ".unpick";

	default void collectUnpickDefinitions(KeratinGradleExtension keratin, MinecraftVersion[] minecraftVersions, File dir, File[] files) throws Exception {
		BufferedWriter[] writers = new BufferedWriter[minecraftVersions.length];

        UnpickVersion unpickVersion = keratin.getUnpickVersion().get();

		try {
			for (int i = 0; i < minecraftVersions.length; i++) {
				writers[i] = new BufferedWriter(new FileWriter(files[i]));

				// headers from src files are skipped
				// (we don't want headers in the middle of the file)
				writers[i].write(unpickVersion.getHeader());
				writers[i].newLine();
			}

			collectUnpickDefinitions(keratin, unpickVersion, minecraftVersions, dir, writers);
		} finally {
			for (BufferedWriter writer : writers) {
				if (writer != null) {
					try {
						writer.close();
					} catch (IOException ioe) {
						keratin.getProject().getLogger().warn("error closing file writer", ioe);
					}
				}
			}
		}
	}

	private static void collectUnpickDefinitions(KeratinGradleExtension keratin, UnpickVersion projectUnpickVersion, MinecraftVersion[] minecraftVersions, File dir, BufferedWriter[] writers) throws Exception {
		for (File file : dir.listFiles()) {
			if (file.isFile() && file.getName().endsWith(FILE_EXTENSION)) {
				exportUnpickDefinitions(keratin, projectUnpickVersion, minecraftVersions, file, writers);
			}
			if (file.isDirectory()) {
				collectUnpickDefinitions(keratin, projectUnpickVersion, minecraftVersions, file, writers);
			}
		}
	}

	private static void exportUnpickDefinitions(KeratinGradleExtension keratin, UnpickVersion projectUnpickVersion, MinecraftVersion[] minecraftVersions, File file, BufferedWriter[] writers) throws Exception {
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line = reader.readLine();
            UnpickVersion unpickVersion = UnpickVersion.parse(line);

            if (unpickVersion != projectUnpickVersion) {
                throw new RuntimeException("Unpick version in file " + file + " uses " + unpickVersion + " while project excepts " + projectUnpickVersion);
            }

			switch (unpickVersion) {
                case V1, V2: {
                    exportV2UnpickDefinitions(reader, keratin, minecraftVersions, writers);
                    break;
                }
                case V3, V4: {
                    exportV3UnpickDefinitions(reader, keratin, minecraftVersions, writers);
                    break;
                }
                case null, default: throw new RuntimeException("Unsupported unpick version: " + line + ", in file: " + file);
            }
		} catch (Exception e) {
			throw new IOException("error exporting unpick definitions from " + file, e);
		}
	}

    private static void exportV2UnpickDefinitions(BufferedReader reader, KeratinGradleExtension keratin, MinecraftVersion[] minecraftVersions, BufferedWriter[] writers) throws IOException {
        boolean[] acceptsLines = new boolean[minecraftVersions.length];
        Arrays.fill(acceptsLines, true);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue; // do not export empty lines
            }

            if (line.charAt(0) == '#') {
                // special comments define mc version ranges where
                // the lines below them are allowed to go
                if (line.startsWith("###")) {
                    Arrays.fill(acceptsLines, true); // accept lines by default

                    line = line.substring(3);
                    line = line.trim();

                    Matcher mcVersionRange = Patterns.OPTIONAL_MC_VERSION_RANGE.matcher(line);

                    if (mcVersionRange.matches()) {
                        String versionA = mcVersionRange.group(1);
                        String versionB = mcVersionRange.group(2);

                        MinecraftVersion minecraftVersionA = (versionA == null) ? null : keratin.getMinecraftVersion(versionA);
                        MinecraftVersion minecraftVersionB = (versionB == null) ? null : keratin.getMinecraftVersion(versionB);

                        for (int i = 0; i < minecraftVersions.length; i++) {
                            MinecraftVersion minecraftVersion = minecraftVersions[i];

                            // if neither of two versions has shared versioning
                            // a common side must exist for comparison to be possible
                            if (!minecraftVersion.hasSharedVersioning()) {
                                if (minecraftVersionA != null && !minecraftVersionA.hasSharedVersioning() && !minecraftVersion.hasCommonSide(minecraftVersionA)) {
                                    acceptsLines[i] = false;
                                }
                                if (minecraftVersionB != null && !minecraftVersionB.hasSharedVersioning() && !minecraftVersion.hasCommonSide(minecraftVersionB)) {
                                    acceptsLines[i] = false;
                                }
                            }

                            if (acceptsLines[i]) {
                                // skip this check if one of the above already failed
                                if ((minecraftVersionA != null && minecraftVersion.compareTo(minecraftVersionA) < 0)
                                        || (minecraftVersionB != null && minecraftVersion.compareTo(minecraftVersionB) > 0)) {
                                    acceptsLines[i] = false; // mc version is not contained in the version range
                                }
                            }
                        }
                    } else {
                        try {
                            MinecraftVersion version = keratin.getMinecraftVersion(line);

                            for (int i = 0; i < minecraftVersions.length; i++) {
                                MinecraftVersion minecraftVersion = minecraftVersions[i];

                                if (minecraftVersion.compareTo(version) != 0) {
                                    acceptsLines[i] = false;
                                }
                            }
                        } catch (Exception e) {
                            // ignore : this line may just be a comment?
                        }
                    }
                }

                continue; // do not export comments
            }

            for (int i = 0; i < minecraftVersions.length; i++) {
                if (acceptsLines[i]) {
                    writers[i].write(line);
                    writers[i].newLine();
                }
            }
        }
    }

    private static void exportV3UnpickDefinitions(BufferedReader reader, KeratinGradleExtension keratin, MinecraftVersion[] minecraftVersions, BufferedWriter[] writers) throws IOException {
        // TODO: Proper version range support for v3 and v4
        boolean[] acceptsLines = new boolean[minecraftVersions.length];
        Arrays.fill(acceptsLines, true);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue; // do not export empty lines
            }

            for (int i = 0; i < minecraftVersions.length; i++) {
                if (acceptsLines[i]) {
                    writers[i].write(line);
                    writers[i].newLine();
                }
            }
        }
    }

	default void unnestUnpickDefinitions(File input, File output, File nests) throws IOException {
		_unnestUnpickDefinitions(input, output, nests);
	}

	static void _unnestUnpickDefinitions(File input, File output, File nests) throws IOException {
		Map<String, String> mappings = MappingUtils.buildUnnestingTranslations(nests.toPath());

        Reader inputStreamReader = new InputStreamReader(new FileInputStream(input));
        LineNumberReader lineReader = new LineNumberReader(inputStreamReader);
        String version = lineReader.readLine();
        inputStreamReader.reset();
        UnpickVersion unpickVersion = UnpickVersion.parse(version);

        switch (unpickVersion) {
            case V1, V2: {
                try (UnpickV2Reader reader = new UnpickV2Reader(inputStreamReader)) {
                    UnpickV2Writer writer = new UnpickV2Writer();
                    reader.accept(new UnpickV2Remapper(mappings, Collections.emptyMap(), Collections.emptyMap(), writer));
                    Files.writeString(output.toPath(), writer.getOutput());
                }
                break;
            }
            case V3, V4: {
                try (UnpickV3Reader reader = new UnpickV3Reader(inputStreamReader)) {
                    UnpickV3Writer writer = new UnpickV3Writer();
                    reader.accept(new UnpickV3UnestingRemapper(mappings, writer));
                    Files.writeString(output.toPath(), writer.getOutput());
                }
                break;
            }
            case null, default: throw new RuntimeException("Unsupported unpick version: " + version);
        }
	}

	interface MapUnpickDefinitionsParameters extends WorkParameters {

		Property<File> getInput();

		Property<File> getMappings();

		Property<String> getSourceNamespace();

		Property<String> getTargetNamespace();

        Property<UnpickVersion> getUnpickVersion();

		Property<File> getOutput();

	}

	abstract class MapUnpickDefinitionsAction implements WorkAction<MapUnpickDefinitionsParameters> {

		@Override
		public void execute() {
			File input = getParameters().getInput().get();
			File mappings = getParameters().getMappings().get();
			String srcNs = getParameters().getSourceNamespace().get();
			String dstNs = getParameters().getTargetNamespace().get();
            UnpickVersion unpickVersion = getParameters().getUnpickVersion().get();
			File output = getParameters().getOutput().get();

			try {
				if (KeratinGradleExtension.validateOutput(output, true)) {
					return;
				}

				switch (unpickVersion) {
                    case V1, V2 -> mapV2UnpickDefinitions(input, mappings, srcNs, dstNs, output);
                    case V3, V4 -> mapV3UnpickDefinitions(input, mappings, srcNs, dstNs, output);
                }
			} catch (IOException e) {
				throw new RuntimeException("error while remapping unpick definitions", e);
			}
		}

        private void mapV2UnpickDefinitions(File input, File mappings, String srcNs, String dstNs, File output) throws IOException {
            Map<String, String> classMappings = new HashMap<>();
            Map<MemberKey, String> methodMappings = new HashMap<>();
            Map<UnpickV2Remapper.FieldKey, String> fieldMappings = new HashMap<>();

            MemoryMappingTree mappingTree = new MemoryMappingTree();
            MappingReader.read(mappings.toPath(), mappingTree);

            for (MappingTree.ClassMapping cls : mappingTree.getClasses()) {
                String className = cls.getName(srcNs);

                if (className == null) {
                    continue;
                }

                classMappings.put(className, cls.getName(dstNs));

                for (MappingTree.MethodMapping mtd : cls.getMethods()) {
                    methodMappings.put(new MemberKey(className, mtd.getName(srcNs), mtd.getDesc(srcNs)), mtd.getName(dstNs));
                }

                for (MappingTree.FieldMapping fld : cls.getFields()) {
                    fieldMappings.put(new UnpickV2Remapper.FieldKey(className, fld.getName(srcNs)), fld.getName(dstNs));
                }
            }

            try (UnpickV2Reader reader = new UnpickV2Reader(new FileInputStream(input))) {
                UnpickV2Writer writer = new UnpickV2Writer();
                reader.accept(new UnpickV2Remapper(classMappings, methodMappings, fieldMappings, writer));
                Files.writeString(output.toPath(), writer.getOutput());
            }
        }

        private void mapV3UnpickDefinitions(File input, File mappings, String srcNs, String dstNs, File output) throws IOException {

            MemoryMappingTree mappingTree = new MemoryMappingTree();
            MappingReader.read(mappings.toPath(), mappingTree);

            int fromM = mappingTree.getNamespaceId(srcNs);
            int toM = mappingTree.getNamespaceId(dstNs);

            try (UnpickV3Reader reader = new UnpickV3Reader(new FileReader(input))) {
                UnpickV3Writer writer = new UnpickV3Writer();
                reader.accept(new UnpickV3Remapper(writer) {
                    @Override
                    protected String mapClassName(String className) {
                        return mappingTree.mapClassName(className.replace('.', '/'), fromM, toM).replace('/', '.');
                    }

                    @Override
                    protected String mapFieldName(String className, String fieldName, String fieldDesc) {
                        MappingTree.FieldMapping fieldMapping = mappingTree.getField(className.replace('.', '/'), fieldName, fieldDesc, fromM);

                        if (fieldMapping == null) {
                            return fieldName;
                        }

                        final String dstName = fieldMapping.getName(toM);
                        return dstName == null ? fieldName : dstName;
                    }

                    @Override
                    protected String mapMethodName(String className, String methodName, String methodDesc) {
                        MappingTree.MethodMapping methodMapping = mappingTree.getMethod(className.replace('.', '/'), methodName, methodDesc, fromM);

                        if (methodMapping == null) {
                            return methodName;
                        }

                        final String dstName = methodMapping.getName(toM);
                        return dstName == null ? methodName : dstName;
                    }

                    @Override
                    protected List<String> getClassesInPackage(String pkg) {
                        return List.of();
                    }

                    @Override
                    protected String getFieldDesc(String className, String fieldName) {
                        return "";
                    }
                });
                Files.writeString(output.toPath(), writer.getOutput());
            }
        }
	}
}
