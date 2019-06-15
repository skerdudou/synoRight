package sk.synoAdmin.synology.pojo;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SynoLoginResponse {
	private boolean success;
	private boolean isAdmin;

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(final boolean success) {
		this.success = success;
	}

	public boolean isAdmin() {
		return isAdmin;
	}

	public void setAdmin(final boolean isAdmin) {
		this.isAdmin = isAdmin;
	}

	@JsonProperty("data")
	private void unpackData(final Map<String, Object> data) {
		isAdmin = (Boolean) data.get("is_admin");
	}

}
