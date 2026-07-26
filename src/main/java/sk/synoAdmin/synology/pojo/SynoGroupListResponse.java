package sk.synoAdmin.synology.pojo;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SynoGroupListResponse {

	private boolean success;
	private final List<SynoGroup> groups = new ArrayList<>();

	@JsonProperty("data")
	private void unpackData(final JsonNode data) {
		final JsonNode groupsNode = data == null ? null : data.get("groups");
		if (groupsNode == null) {
			return;
		}
		for (final JsonNode group : groupsNode) {
			groups.add(new SynoGroup(group.get("gid").asInt(), group.get("name").asText()));
		}
	}

	public boolean isSuccess() {
		return success;
	}

	public List<SynoGroup> getGroups() {
		return groups;
	}

	public static final class SynoGroup {
		private final int gid;
		private final String name;

		public SynoGroup(final int gid, final String name) {
			this.gid = gid;
			this.name = name;
		}

		public int getGid() {
			return gid;
		}

		public String getName() {
			return name;
		}
	}
}
