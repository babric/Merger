/*
 * Copyright (c) 2016, 2017, 2018 Adrian Siekierka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.merge;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class JarMerger implements AutoCloseable {
	public class Entry {
		public final Path path;
		public final BasicFileAttributes metadata;
		public final byte[] data;

		public Entry(Path path, BasicFileAttributes metadata, byte[] data) {
			this.path = path;
			this.metadata = metadata;
			this.data = data;
		}
	}

	private static final ClassMerger CLASS_MERGER = new ClassMerger();
	private final /*StitchUtil.*/FileSystem/*Delegate inputClientFs, inputServerFs,*/ outputFs;
	private final Path inputClient, inputServer;
	private final Map<String, Entry> entriesClient, entriesServer;
	private final Set<String> entriesAll;
	private boolean removeSnowmen = false;
	private boolean offsetSyntheticsParams = false;

	//Based on StitchUtil's File accepting version
	private static FileSystem getJarFileSystem(Path path, boolean create) throws IOException {
		URI jarUri;
		try {
			jarUri = new URI("jar:file", null, path.toUri().getPath(), "");
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}

		try {
			return FileSystems.newFileSystem(jarUri, create ? Collections.singletonMap("create", "true") : Collections.emptyMap());
		} catch (FileSystemAlreadyExistsException e) {
			throw new RuntimeException("Error " + (create ? "creating" : "getting") + " file system", e);
		}
	}
	//Up to here

	public JarMerger(/*File*/Path inputClient, /*File*/Path inputServer, /*File*/Path output) throws IOException {
		/*if (output.exists()) {
			if (!output.delete()) {
				throw new IOException("Could not delete " + output.getName());
			}
		}*/
		assert Files.notExists(output); //Let's just presume the output path is clear

		this.inputClient = /*(inputClientFs = StitchUtil.getJarFileSystem(inputClient, false)).get().getPath("/")*/inputClient;
		this.inputServer = /*(inputServerFs = StitchUtil.getJarFileSystem(inputServer, false)).get().getPath("/")*/inputServer;
		outputFs = /*StitchUtil.*/getJarFileSystem(output, true);

		entriesClient = new HashMap<>();
		entriesServer = new HashMap<>();
		entriesAll = new TreeSet<>();
	}

	public void enableSnowmanRemoval() {
		removeSnowmen = true;
	}

	public void enableSyntheticParamsOffset() {
		offsetSyntheticsParams = true;
	}

	@Override
	public void close() throws IOException {
		//inputClientFs.close();
		//inputServerFs.close();
		outputFs.close();
	}

	private void readToMap(Map<String, Entry> map, Path input, boolean isServer) {
		try {
			Files.walkFileTree(input, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes attr) throws IOException {
					if (attr.isDirectory()) {
						return FileVisitResult.CONTINUE;
					}

					if (!path.getFileName().toString().endsWith(".class")) {
						if (path.toString().equals("/META-INF/MANIFEST.MF")) {
							map.put("META-INF/MANIFEST.MF", new Entry(path, attr, "Manifest-Version: 1.0\nMain-Class: net.minecraft.client.Main\n".getBytes(StandardCharsets.UTF_8)));
						} else {
							if (path.toString().startsWith("/META-INF/")) {
								if (path.toString().endsWith(".SF") || path.toString().endsWith(".RSA")) {
									return FileVisitResult.CONTINUE;
								}
							}

							map.put(path.toString().substring(1), new Entry(path, attr, null));
						}

						return FileVisitResult.CONTINUE;
					}

					byte[] output = Files.readAllBytes(path);
					map.put(path.toString().substring(1), new Entry(path, attr, output));
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void add(Entry entry) throws IOException {
		Path outPath = outputFs/*.get()*/.getPath(entry.path.toString());
		if (outPath.getParent() != null) {
			Files.createDirectories(outPath.getParent());
		}

		if (entry.data != null) {
			Files.write(outPath, entry.data, StandardOpenOption.CREATE_NEW);
		} else {
			Files.copy(entry.path, outPath);
		}

		Files.getFileAttributeView(entry.path, BasicFileAttributeView.class).setTimes(entry.metadata.creationTime(), entry.metadata.lastAccessTime(), entry.metadata.lastModifiedTime());
	}

	public void merge() throws IOException {
		ExecutorService service = Executors.newFixedThreadPool(2);
		service.submit(() -> readToMap(entriesClient, inputClient, false));
		service.submit(() -> readToMap(entriesServer, inputServer, true));
		service.shutdown();
		try {
			service.awaitTermination(1, TimeUnit.HOURS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		entriesAll.addAll(entriesClient.keySet());
		entriesAll.addAll(entriesServer.keySet());

		List<Entry> entries = entriesAll.parallelStream().map((entry) -> {
			boolean isClass = entry.endsWith(".class");
			boolean isMinecraft = entriesClient.containsKey(entry) || entry.startsWith("net/minecraft") || !entry.contains("/");
			Entry result;
			String side = null;

			Entry entry1 = entriesClient.get(entry);
			Entry entry2 = entriesServer.get(entry);

			if (entry1 != null && entry2 != null) {
				if (Arrays.equals(entry1.data, entry2.data)) {
					result = entry1;
				} else {
					if (isClass) {
						result = new Entry(entry1.path, entry1.metadata, CLASS_MERGER.merge(entry1.data, entry2.data));
					} else {
						System.err.println("Common non-class resource: " + entry);
						// FIXME: More heuristics?
						result = entry1;
					}
				}
			} else if ((result = entry1) != null) {
				side = "CLIENT";
			} else if ((result = entry2) != null) {
				side = "SERVER";
			}

			if (isClass && !isMinecraft && "SERVER".equals(side)) {
				// Server bundles libraries, client doesn't - skip them
				return null;
			}

			if (result != null) {
				if (isMinecraft && isClass) {
					byte[] data = result.data;
					ClassReader reader = new ClassReader(data);
					ClassWriter writer = new ClassWriter(0);
					ClassVisitor visitor = writer;

					if (side != null) {
						visitor = new ClassMerger.SidedClassVisitor(Opcodes.ASM7, visitor, side);
					}

					if (removeSnowmen) {
						//visitor = new SnowmanClassVisitor(Opcodes.ASM7, visitor);
						throw new UnsupportedOperationException(); //Shouldn't be needed given there's no LVT
					}

					if (offsetSyntheticsParams) {
						//visitor = new SyntheticParameterClassVisitor(Opcodes.ASM7, visitor);
						throw new UnsupportedOperationException(); //Shouldn't be needed given there's no annotations
					}

					if (visitor != writer) {
						reader.accept(visitor, 0);
						data = writer.toByteArray();
						result = new Entry(result.path, result.metadata, data);
					}
				}

				return result;
			} else {
				return null;
			}
		}).filter(Objects::nonNull).collect(Collectors.toList());

		for (Entry e : entries) {
			add(e);
		}
	}
}