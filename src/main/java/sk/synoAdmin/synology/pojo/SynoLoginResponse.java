package sk.synoAdmin.synology.pojo;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SynoLoginResponse {
	private boolean success;
	@JsonProperty("data")
	private SynoLoginSuccessDataResponse successData;
	@JsonProperty("error")
	private SynoLoginErrorResponse error;

	public boolean isSuccess() {
		return success;
	}

	public SynoLoginSuccessDataResponse getSuccessData() {
		return successData;
	}

	public SynoLoginErrorResponse getError() {
		return error;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SynoLoginSuccessDataResponse {
		private String account;
		@JsonProperty("device_id")
		private String deviceId;
		private String sid;
		private String synotoken;

		public String getAccount() {
			return account;
		}

		public String getDeviceId() {
			return deviceId;
		}

		public String getSid() {
			return sid;
		}

		public String getSynotoken() {
			return synotoken;
		}
	}

	public static class SynoLoginErrorResponse {
		private Integer code;
		private  String errorType;

		@JsonProperty("errors")
		private void unpackData(final JsonNode errors) {
			errorType = errors.get("types").get(0).get("type").asText();
		}

		public Integer getCode() {
			return code;
		}

		public void setCode(Integer code) {
			this.code = code;
		}

		public String getErrorType() {
			return errorType;
		}
	}
}
