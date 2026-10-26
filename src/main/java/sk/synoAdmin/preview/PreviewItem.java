package sk.synoAdmin.preview;

import java.nio.file.Path;
import java.util.Optional;

import sk.synoAdmin.synology.SynoPhotoItem;

/**
 * Élément à prévisualiser : chemin local attendu (dossier local + nom) et vignette Photos
 * (vide pour un fichier venu du partage).
 */
public record PreviewItem(String fileName, Path file, Optional<SynoPhotoItem.Thumb> thumb) {
}
