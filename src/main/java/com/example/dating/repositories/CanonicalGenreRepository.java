package com.example.dating.repositories;

import com.example.dating.models.matching.dao.CanonicalGenre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing canonical music genres.
 */
@Repository
public interface CanonicalGenreRepository extends JpaRepository<CanonicalGenre, String> {

    /**
     * Find a genre by its canonical name.
     */
    Optional<CanonicalGenre> findByName(String name);

    /**
     * Find genres by display name (case-insensitive).
     */
    Optional<CanonicalGenre> findByDisplayNameIgnoreCase(String displayName);

    /**
     * Find all primary genres (those shown in UI).
     * Ordered by display_order ascending.
     */
    @Query("SELECT g FROM CanonicalGenre g WHERE g.isPrimary = true ORDER BY g.displayOrder ASC, g.name ASC")
    List<CanonicalGenre> findAllPrimaryGenres();

    /**
     * Find all child genres of a parent genre.
     */
    List<CanonicalGenre> findByParentGenre(CanonicalGenre parentGenre);

    /**
     * Find all top-level genres (no parent).
     */
    @Query("SELECT g FROM CanonicalGenre g WHERE g.parentGenre IS NULL ORDER BY g.displayOrder ASC, g.name ASC")
    List<CanonicalGenre> findAllTopLevelGenres();

    /**
     * Search genres by name (fuzzy match for autocomplete).
     */
    @Query("SELECT g FROM CanonicalGenre g WHERE " +
           "LOWER(g.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(g.displayName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "ORDER BY g.displayOrder ASC, g.name ASC")
    List<CanonicalGenre> searchByName(@Param("searchTerm") String searchTerm);

    /**
     * Find genre by Spotify alias.
     * Searches the comma-separated spotify_aliases field.
     */
    @Query("SELECT g FROM CanonicalGenre g WHERE " +
           "LOWER(g.spotifyAliases) LIKE LOWER(CONCAT('%', :spotifyGenre, '%'))")
    List<CanonicalGenre> findBySpotifyAlias(@Param("spotifyGenre") String spotifyGenre);

    /**
     * Check if a genre exists by name.
     */
    boolean existsByName(String name);
}
