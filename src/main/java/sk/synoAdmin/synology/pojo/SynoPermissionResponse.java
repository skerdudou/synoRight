package sk.synoAdmin.synology.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SynoPermissionResponse {

	private Integer id;
	private String name;

	@JsonProperty("parent")
	private Integer parentId;
	private List<Integer> browseGrpIds;

	@JsonProperty("additional")
	private void unpackData(final JsonNode additional) {
		browseGrpIds = new ArrayList<>();
		for (var permission : additional.get("sharing_info").get("permission")) {
			browseGrpIds.add(permission.get("id").asInt());
		}
	}

	public Integer getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Integer getParentId() {
		return parentId;
	}

	public List<Integer> getBrowseGrpIds() {
		return browseGrpIds;
	}
}
