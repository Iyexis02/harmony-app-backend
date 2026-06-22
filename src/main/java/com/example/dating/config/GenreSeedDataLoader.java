package com.example.dating.config;

import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.repositories.CanonicalGenreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Seeds the database with canonical music genres on application startup.
 * Only runs if the canonical_genres table is empty.
 *
 * Genres are based on Spotify's genre taxonomy, focusing on the most common/popular ones.
 */
@Slf4j
@Component
@Order(1) // Run early in startup sequence
@RequiredArgsConstructor
public class GenreSeedDataLoader implements CommandLineRunner {

    private final CanonicalGenreRepository genreRepository;

    // Store parent genres for reference
    private final Map<String, CanonicalGenre> genreMap = new HashMap<>();

    @Override
    @Transactional
    public void run(String... args) {
        if (genreRepository.count() > 0) {
            log.info("Canonical genres already exist. Skipping seed data.");
            return;
        }

        log.info("Seeding canonical genres...");

        // Create all genres
        seedTopLevelGenres();
        seedRockSubgenres();
        seedPopSubgenres();
        seedHipHopSubgenres();
        seedElectronicSubgenres();
        seedJazzSubgenres();
        seedClassicalSubgenres();
        seedCountrySubgenres();
        seedRnBSubgenres();
        seedMetalSubgenres();
        seedFolkSubgenres();
        seedLatinSubgenres();
        seedOtherGenres();

        log.info("Successfully seeded {} canonical genres", genreRepository.count());
    }

    private void seedTopLevelGenres() {
        createGenre("rock", "Rock", null, "rock,rock music", true, 10);
        createGenre("pop", "Pop", null, "pop,pop music", true, 20);
        createGenre("hip-hop", "Hip Hop", null, "hip hop,hip-hop,rap,hiphop", true, 30);
        createGenre("electronic", "Electronic", null, "electronic,edm,dance", true, 40);
        createGenre("jazz", "Jazz", null, "jazz,jazz music", true, 50);
        createGenre("classical", "Classical", null, "classical,classical music", true, 60);
        createGenre("country", "Country", null, "country,country music", true, 70);
        createGenre("rnb", "R&B", null, "r&b,r & b,rnb,rhythm and blues", true, 80);
        createGenre("metal", "Metal", null, "metal,heavy metal", true, 90);
        createGenre("folk", "Folk", null, "folk,folk music", true, 100);
        createGenre("latin", "Latin", null, "latin,latin music", true, 110);
        createGenre("blues", "Blues", null, "blues,blues music", true, 120);
        createGenre("reggae", "Reggae", null, "reggae,reggae music", true, 130);
        createGenre("soul", "Soul", null, "soul,soul music", true, 140);
        createGenre("funk", "Funk", null, "funk,funk music", true, 150);
    }

    private void seedRockSubgenres() {
        CanonicalGenre rock = genreMap.get("rock");
        createGenre("indie-rock", "Indie Rock", rock, "indie rock,indie_rock,indie", true, 11);
        createGenre("alternative-rock", "Alternative Rock", rock, "alternative rock,alternative,alt-rock,alt rock", true, 12);
        createGenre("punk-rock", "Punk Rock", rock, "punk rock,punk,punk_rock", true, 13);
        createGenre("hard-rock", "Hard Rock", rock, "hard rock,hard_rock", true, 14);
        createGenre("progressive-rock", "Progressive Rock", rock, "progressive rock,prog rock,prog-rock,progressive_rock", false, 15);
        createGenre("psychedelic-rock", "Psychedelic Rock", rock, "psychedelic rock,psychedelic,psych rock", false, 16);
        createGenre("garage-rock", "Garage Rock", rock, "garage rock,garage_rock,garage", false, 17);
        createGenre("post-rock", "Post-Rock", rock, "post-rock,post rock,postrock", false, 18);
        createGenre("art-rock", "Art Rock", rock, "art rock,art-rock,art_rock", false, 19);
    }

    private void seedPopSubgenres() {
        CanonicalGenre pop = genreMap.get("pop");
        createGenre("indie-pop", "Indie Pop", pop, "indie pop,indie_pop,indiepop", true, 21);
        createGenre("synth-pop", "Synth Pop", pop, "synth-pop,synth pop,synthpop", true, 22);
        createGenre("dance-pop", "Dance Pop", pop, "dance pop,dance-pop,dancepop", true, 23);
        createGenre("electropop", "Electropop", pop, "electropop,electro pop,electro-pop", true, 24);
        createGenre("k-pop", "K-Pop", pop, "k-pop,kpop,korean pop", true, 25);
        createGenre("dream-pop", "Dream Pop", pop, "dream pop,dream-pop,dreampop", false, 26);
        createGenre("art-pop", "Art Pop", pop, "art pop,art-pop,artpop", false, 27);
    }

    private void seedHipHopSubgenres() {
        CanonicalGenre hiphop = genreMap.get("hip-hop");
        createGenre("trap", "Trap", hiphop, "trap,trap music", true, 31);
        createGenre("drill", "Drill", hiphop, "drill,drill music", true, 32);
        createGenre("conscious-hip-hop", "Conscious Hip Hop", hiphop, "conscious hip hop,conscious rap", false, 33);
        createGenre("gangsta-rap", "Gangsta Rap", hiphop, "gangsta rap,gangsta", false, 34);
        createGenre("underground-hip-hop", "Underground Hip Hop", hiphop, "underground hip hop,underground rap", false, 35);
        createGenre("old-school-hip-hop", "Old School Hip Hop", hiphop, "old school hip hop,old school rap", false, 36);
    }

    private void seedElectronicSubgenres() {
        CanonicalGenre electronic = genreMap.get("electronic");
        createGenre("house", "House", electronic, "house,house music", true, 41);
        createGenre("techno", "Techno", electronic, "techno,techno music", true, 42);
        createGenre("trance", "Trance", electronic, "trance,trance music", true, 43);
        createGenre("dubstep", "Dubstep", electronic, "dubstep,dub step", true, 44);
        createGenre("drum-and-bass", "Drum & Bass", electronic, "drum and bass,drum & bass,dnb,drum n bass", true, 45);
        createGenre("ambient", "Ambient", electronic, "ambient,ambient music", true, 46);
        createGenre("downtempo", "Downtempo", electronic, "downtempo,down tempo", false, 47);
        createGenre("future-bass", "Future Bass", electronic, "future bass,future_bass,futurebass", false, 48);
        createGenre("bass", "Bass Music", electronic, "bass,bass music", false, 49);
        createGenre("chillwave", "Chillwave", electronic, "chillwave,chill wave", false, 410);
    }

    private void seedJazzSubgenres() {
        CanonicalGenre jazz = genreMap.get("jazz");
        createGenre("smooth-jazz", "Smooth Jazz", jazz, "smooth jazz,smooth_jazz", false, 51);
        createGenre("bebop", "Bebop", jazz, "bebop,be-bop", false, 52);
        createGenre("swing", "Swing", jazz, "swing,swing music", false, 53);
        createGenre("latin-jazz", "Latin Jazz", jazz, "latin jazz,latin_jazz", false, 54);
        createGenre("contemporary-jazz", "Contemporary Jazz", jazz, "contemporary jazz,modern jazz", false, 55);
    }

    private void seedClassicalSubgenres() {
        CanonicalGenre classical = genreMap.get("classical");
        createGenre("baroque", "Baroque", classical, "baroque,baroque music", false, 61);
        createGenre("romantic", "Romantic", classical, "romantic,romantic music,romantic classical", false, 62);
        createGenre("contemporary-classical", "Contemporary Classical", classical, "contemporary classical,modern classical", false, 63);
        createGenre("opera", "Opera", classical, "opera,operatic", false, 64);
    }

    private void seedCountrySubgenres() {
        CanonicalGenre country = genreMap.get("country");
        createGenre("country-pop", "Country Pop", country, "country pop,country_pop", false, 71);
        createGenre("bluegrass", "Bluegrass", country, "bluegrass,blue grass", false, 72);
        createGenre("outlaw-country", "Outlaw Country", country, "outlaw country,outlaw_country", false, 73);
        createGenre("alt-country", "Alt-Country", country, "alt-country,alternative country,alt country", false, 74);
    }

    private void seedRnBSubgenres() {
        CanonicalGenre rnb = genreMap.get("rnb");
        createGenre("neo-soul", "Neo Soul", rnb, "neo soul,neo-soul,neosoul", false, 81);
        createGenre("contemporary-rnb", "Contemporary R&B", rnb, "contemporary r&b,contemporary rnb,modern r&b", true, 82);
        createGenre("quiet-storm", "Quiet Storm", rnb, "quiet storm,quiet_storm", false, 83);
    }

    private void seedMetalSubgenres() {
        CanonicalGenre metal = genreMap.get("metal");
        createGenre("death-metal", "Death Metal", metal, "death metal,death_metal", false, 91);
        createGenre("black-metal", "Black Metal", metal, "black metal,black_metal", false, 92);
        createGenre("thrash-metal", "Thrash Metal", metal, "thrash metal,thrash_metal,thrash", false, 93);
        createGenre("doom-metal", "Doom Metal", metal, "doom metal,doom_metal,doom", false, 94);
        createGenre("progressive-metal", "Progressive Metal", metal, "progressive metal,prog metal,prog_metal", false, 95);
        createGenre("metalcore", "Metalcore", metal, "metalcore,metal core", false, 96);
    }

    private void seedFolkSubgenres() {
        CanonicalGenre folk = genreMap.get("folk");
        createGenre("indie-folk", "Indie Folk", folk, "indie folk,indie_folk,indiefolk", true, 101);
        createGenre("folk-rock", "Folk Rock", folk, "folk rock,folk_rock,folkrock", false, 102);
        createGenre("americana", "Americana", folk, "americana,american folk", false, 103);
        createGenre("singer-songwriter", "Singer-Songwriter", folk, "singer-songwriter,singer songwriter", true, 104);
    }

    private void seedLatinSubgenres() {
        CanonicalGenre latin = genreMap.get("latin");
        createGenre("reggaeton", "Reggaeton", latin, "reggaeton,reggaetón", true, 111);
        createGenre("salsa", "Salsa", latin, "salsa,salsa music", false, 112);
        createGenre("bachata", "Bachata", latin, "bachata,bachata music", false, 113);
        createGenre("latin-pop", "Latin Pop", latin, "latin pop,latin_pop", true, 114);
        createGenre("regional-mexican", "Regional Mexican", latin, "regional mexican,mexican regional", false, 115);
        createGenre("bossa-nova", "Bossa Nova", latin, "bossa nova,bossa_nova,bossanova", false, 116);
    }

    private void seedOtherGenres() {
        createGenre("indie", "Indie", null, "indie,independent", true, 200);
        createGenre("alternative", "Alternative", null, "alternative,alt", true, 210);
        createGenre("acoustic", "Acoustic", null, "acoustic,acoustic music", true, 220);
        createGenre("instrumental", "Instrumental", null, "instrumental,instrumentals", false, 230);
        createGenre("soundtrack", "Soundtrack", null, "soundtrack,soundtracks,film music,movie music", false, 240);
        createGenre("world", "World Music", null, "world,world music,international", false, 250);
        createGenre("afrobeat", "Afrobeat", null, "afrobeat,afro beat,afrobeats", true, 260);
        createGenre("gospel", "Gospel", null, "gospel,gospel music", false, 270);
        createGenre("punk", "Punk", null, "punk,punk music", true, 280);
        createGenre("grunge", "Grunge", null, "grunge,grunge music", true, 290);
        createGenre("emo", "Emo", null, "emo,emo music", false, 300);
        createGenre("shoegaze", "Shoegaze", null, "shoegaze,shoe gaze", false, 310);
        createGenre("new-wave", "New Wave", null, "new wave,new_wave,newwave", false, 320);
        createGenre("disco", "Disco", null, "disco,disco music", false, 330);
        createGenre("ska", "Ska", null, "ska,ska music", false, 340);
    }

    private void createGenre(String name, String displayName, CanonicalGenre parent,
                            String spotifyAliases, boolean isPrimary, int displayOrder) {
        CanonicalGenre genre = CanonicalGenre.builder()
                .name(name)
                .displayName(displayName)
                .parentGenre(parent)
                .spotifyAliases(spotifyAliases)
                .isPrimary(isPrimary)
                .displayOrder(displayOrder)
                .build();

        CanonicalGenre saved = genreRepository.save(genre);
        genreMap.put(name, saved);

        log.debug("Created genre: {} (parent: {})", displayName, parent != null ? parent.getDisplayName() : "none");
    }
}
