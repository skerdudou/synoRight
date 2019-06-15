package sk.synoAdmin.synology.pojo;

import java.util.List;

public class SynoAccessRightResponse {

	private List<SynoPermissionResponse> all;

	public List<SynoPermissionResponse> getAll() {
		return all;
	}

	public void setAll(final List<SynoPermissionResponse> all) {
		this.all = all;
	}

}
