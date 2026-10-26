package sk.synoAdmin.synology;

import java.util.Optional;
import java.util.Set;

/**
 * Élément (photo ou vidéo) d'un dossier de Synology Photos, avec sa vignette si le NAS en déclare une.
 */
public record SynoPhotoItem(int id, String fileName, String type, Optional<Thumb> thumb) {

	/**
	 * Vignette d'un élément : identifiants à fournir pour la télécharger et tailles prêtes ("sm", "xl", ...).
	 */
	public record Thumb(int unitId, String cacheKey, Set<String> readySizes) {
	}

	/**
	 * true si la vignette de cette taille est générée et téléchargeable.
	 */
	public boolean ready(final String size) {
		return thumb.isPresent() && thumb.get().readySizes().contains(size);
	}
}
