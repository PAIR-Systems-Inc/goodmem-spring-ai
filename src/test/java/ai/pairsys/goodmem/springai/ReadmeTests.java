package ai.pairsys.goodmem.springai;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The README is copied into applications, so what it names must exist. 0.2.1's
 * quickstart imported {@code RetrievalAugmentationAdvisor} from a package Spring AI 1.0.0
 * does not have, and the snippet did not compile.
 */
class ReadmeTests {

	private static final Pattern JAVA_BLOCK = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL);

	private static final Pattern IMPORT = Pattern.compile("^import\\s+([\\w.]+);\\s*$", Pattern.MULTILINE);

	private static final Pattern TOOL_NAME = Pattern.compile("`(goodmem_[a-z_]+)`");

	private static String readme() throws IOException {
		return Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
	}

	@Test
	void everyClassTheReadmeImportsExists() throws IOException {
		List<String> imports = new ArrayList<>();
		Matcher block = JAVA_BLOCK.matcher(readme());
		while (block.find()) {
			Matcher imp = IMPORT.matcher(block.group(1));
			while (imp.find()) {
				imports.add(imp.group(1));
			}
		}
		assertThat(imports).isNotEmpty();
		List<String> missing = new ArrayList<>();
		for (String name : imports) {
			try {
				Class.forName(name, false, getClass().getClassLoader());
			}
			catch (ClassNotFoundException ex) {
				missing.add(name);
			}
		}
		assertThat(missing).as("README imports that do not resolve").isEmpty();
	}

	@Test
	void everyToolNameTheReadmeMentionsIsARealTool() throws IOException {
		Set<String> real = new TreeSet<>();
		for (Class<?> type : List.of(GoodMemSearchTool.class, GoodMemAdminTools.class, GoodMemUploadTool.class)) {
			for (Method method : type.getDeclaredMethods()) {
				Tool tool = method.getAnnotation(Tool.class);
				if (tool != null) {
					real.add(tool.name());
				}
			}
		}
		String readme = readme();
		// The metadata-key table names goodmem_* keys that are not tools; set it aside.
		int table = readme.indexOf("| metadata key |");
		assertThat(table).isNotNegative();
		String withoutMetadataTable = readme.substring(0, table) + readme.substring(readme.indexOf("\n\n", table));
		Set<String> named = new TreeSet<>();
		Matcher m = TOOL_NAME.matcher(withoutMetadataTable);
		while (m.find()) {
			named.add(m.group(1));
		}
		assertThat(named).hasSizeGreaterThanOrEqualTo(11);
		assertThat(real).containsAll(named);
	}

}
