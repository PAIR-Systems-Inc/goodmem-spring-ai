package ai.pairsys.goodmem.springai;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The validator every id passes; the wire behaviour is in {@link GoodMemIdPathTraversalTests}. */
class GoodMemIdsTests {

	private static final String U = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

	@Test
	void aUuidIsAcceptedInAnyCaseAndLowerCased() {
		assertThat(GoodMemIds.requireUuid(U, "spaceId")).isEqualTo(U);
		assertThat(GoodMemIds.requireUuid(U.toUpperCase(), "spaceId")).isEqualTo(U);
		assertThat(GoodMemIds.requireUuid("0A1b2C3d-4E5f-4A6b-8C7d-9E0f1A2b3C4d", "spaceId")).isEqualTo(U);
		assertThat(GoodMemIds.requireUuid("00000000-0000-0000-0000-000000000000", "spaceId"))
			.isEqualTo("00000000-0000-0000-0000-000000000000");
	}

	@Test
	void anythingElseIsRefused() {
		List<String> refused = Arrays.asList(null, "", " ", U.replace("-", ""), "{" + U + "}", "urn:uuid:" + U,
				U.substring(1), U + "0", U.replace('a', 'g'), U + "\n", U + "\r\n", "\n" + U, U.replace('0', '０'),
				U.replace("-", "‐"), U.replace('-', '_'), "0a1b2c3d4-e5f-4a6b-8c7d-9e0f1a2b3c4d");
		for (String value : refused) {
			assertThatThrownBy(() -> GoodMemIds.requireUuid(value, "memoryId")).as(String.valueOf(value))
				.isInstanceOf(IllegalArgumentException.class)
				.isInstanceOf(GoodMemIds.InvalidIdException.class);
		}
	}

	@Test
	void theMessageNamesTheFieldAndNeverRepeatsTheValue() {
		String hostile = "../spaces/" + U + "\nWARN forged log line";

		assertThatThrownBy(() -> GoodMemIds.requireUuid(hostile, "embedderId"))
			.hasMessageStartingWith("embedderId must be a UUID")
			.hasMessageContaining("nothing was sent")
			.hasMessageNotContaining(U)
			.hasMessageNotContaining("\n");
	}

}
