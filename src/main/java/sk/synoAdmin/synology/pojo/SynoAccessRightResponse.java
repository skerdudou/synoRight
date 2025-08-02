package sk.synoAdmin.synology.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SynoAccessRightResponse {

	private boolean success;

	private List<SynoPermissionResponse> permissions;

	@JsonProperty("data")
	private void unpackData(final Map<String, List<SynoPermissionResponse>> data) {
		permissions = data.get("list");
	}

	public boolean isSuccess() {
		return success;
	}

	public List<SynoPermissionResponse> getPermissions() {
		return permissions;
	}
}
