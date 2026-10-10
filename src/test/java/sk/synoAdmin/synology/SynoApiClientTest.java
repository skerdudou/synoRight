package sk.synoAdmin.synology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sk.synoAdmin.engine.Folder;

class SynoApiClientTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(final String content) {
		try {
			return MAPPER.readTree(content);
		} catch (final JsonProcessingException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private static Folder folderWithId(final String name, final int id) {
		final Folder folder = new Folder(new File(name), null);
		folder.setId(id);
		return folder;
	}

	@Test
	void parseFolderList_dossiersEtGroupesAutorises() {
		final JsonNode response = json("""
				{"success":true,"data":{"list":[
					{"id":12,"name":"/vacances","additional":{"sharing_info":{"permission":[
						{"id":3,"type":"group","role":"download"},
						{"id":5,"type":"group","role":"download"}]}}},
					{"id":15,"name":"/mariage","additional":{"sharing_info":{"permission":[]}}}
				]}}""");

		final List<SynoFolderPermission> folders = SynoApiClient.parseFolderList(response);

		assertEquals(2, folders.size());
		assertEquals(new SynoFolderPermission(12, "/vacances", List.of(3, 5)), folders.get(0));
		assertEquals(new SynoFolderPermission(15, "/mariage", List.of()), folders.get(1));
	}

	@Test
	void parseFolderList_listeVide() {
		assertTrue(SynoApiClient.parseFolderList(json("{\"success\":true,\"data\":{\"list\":[]}}")).isEmpty());
	}

	@Test
	void parseGroupList_gidEtNom() {
		final JsonNode response = json("""
				{"success":true,"data":{"groups":[
					{"gid":3,"name":"famille","description":""},
					{"gid":5,"name":"amis","description":""}
				]}}""");

		final List<SynoGroup> groups = SynoApiClient.parseGroupList(response);

		assertEquals(List.of(new SynoGroup(3, "famille"), new SynoGroup(5, "amis")), groups);
	}

	@Test
	void buildPermissionParam_ajoutsAvecRoleEtSuppressions() {
		final String param = SynoApiClient.buildPermissionParam("3",
				List.of(folderWithId("vacances", 12)),
				List.of(folderWithId("mariage", 15)));

		assertEquals("{\"id\":3,\"type\":\"group\",\"list\":["
				+ "{\"folder_id\":12,\"action\":\"update\",\"role\":\"download\"},"
				+ "{\"folder_id\":15,\"action\":\"delete\"}]}", param);
	}

	@Test
	void buildPermissionParam_suppressionSeule() {
		final String param = SynoApiClient.buildPermissionParam("5", List.of(), List.of(folderWithId("plage", 8)));

		assertEquals("{\"id\":5,\"type\":\"group\",\"list\":[{\"folder_id\":8,\"action\":\"delete\"}]}", param);
	}

	@Test
	void describeLoginError_codesConnus() {
		assertTrue(SynoApiClient.describeLoginError(400).contains("mot de passe incorrect"));
		assertTrue(SynoApiClient.describeLoginError(404).contains("OTP"));
		assertTrue(SynoApiClient.describeLoginError(407).contains("bloquée"));
		assertTrue(SynoApiClient.describeLoginError(999).contains("999"));
	}

	@Test
	void describeIoError_causesReseau() {
		assertTrue(SynoApiClient.describeIoError(new java.net.ConnectException()).contains("injoignable"));
		assertTrue(SynoApiClient.describeIoError(new java.io.IOException(new java.nio.channels.UnresolvedAddressException())).contains("hôte introuvable"));
		assertTrue(SynoApiClient.describeIoError(new javax.net.ssl.SSLHandshakeException("PKIX")).contains("certificat"));
		assertTrue(SynoApiClient.describeIoError(new com.fasterxml.jackson.core.JsonParseException(null, "html")).contains("DSM"));
	}
}
