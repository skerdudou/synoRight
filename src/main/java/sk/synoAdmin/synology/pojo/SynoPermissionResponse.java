package sk.synoAdmin.synology.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SynoPermissionResponse {

	@JsonProperty("_id")
	private String id;

	@JsonProperty("_parent")
	private String parent;

	private String title;
	private boolean browse;
	private boolean manage;
	private boolean upload;

	public String getId() {
		return id;
	}

	public void setId(final String id) {
		this.id = id;
	}

	public String getParent() {
		return parent;
	}

	public void setParent(final String parent) {
		this.parent = parent;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(final String title) {
		this.title = title;
	}

	public boolean isBrowse() {
		return browse;
	}

	public void setBrowse(final boolean browse) {
		this.browse = browse;
	}

	public boolean isManage() {
		return manage;
	}

	public void setManage(final boolean manage) {
		this.manage = manage;
	}

	public boolean isUpload() {
		return upload;
	}

	public void setUpload(final boolean upload) {
		this.upload = upload;
	}

}
