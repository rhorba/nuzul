package ma.nuzul.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed demo data: 6 Moroccan cities x 10 hotels, 2-4 room types each. Every value is derived from the hotel's
 * position in the list (no randomness), so two runs, or two machines, always produce the same data. Hotel names are
 * fictional.
 */
final class SeedCatalog {

    /** A room type to create, with the rooms to stock and the price to charge on each night of the window. */
    record SeedRoomType(
            String name, int capacity, String bedConfig, int rooms, BigDecimal basePriceMad, boolean refundable) {

        /** Friday and Saturday nights cost 20% more, rounded to a whole dirham. Exact BigDecimal, never a double. */
        BigDecimal priceOn(LocalDate night) {
            DayOfWeek day = night.getDayOfWeek();
            if (day == DayOfWeek.FRIDAY || day == DayOfWeek.SATURDAY) {
                return basePriceMad
                        .multiply(WEEKEND_MARKUP)
                        .setScale(0, RoundingMode.HALF_UP)
                        .setScale(2);
            }
            return basePriceMad;
        }
    }

    record SeedHotel(
            String name,
            String city,
            String address,
            int stars,
            List<String> amenities,
            BigDecimal lat,
            BigDecimal lon,
            List<SeedRoomType> roomTypes) {}

    private record City(String name, double lat, double lon, BigDecimal priceFactor, List<String> hotels) {}

    private record Template(String name, int capacity, String bedConfig, int rooms, int basePriceMad) {}

    static final BigDecimal WEEKEND_MARKUP = new BigDecimal("1.20");

    private static final List<City> CITIES = List.of(
            new City(
                    "Marrakech",
                    31.6295,
                    -7.9811,
                    new BigDecimal("1.20"),
                    List.of(
                            "Riad Zahra",
                            "Dar Nakhla",
                            "Riad Bab Doukkala",
                            "Palais Agdal",
                            "Hôtel Jemaa",
                            "Riad Mouassine Bleu",
                            "Kasbah Ourika",
                            "Dar Ennakhil",
                            "Maison Mellah",
                            "Hôtel Palmeraie Sud")),
            new City(
                    "Fès",
                    34.0181,
                    -5.0078,
                    new BigDecimal("1.00"),
                    List.of(
                            "Riad Batha",
                            "Dar Seffarine",
                            "Palais Bab Boujloud",
                            "Riad Andalous",
                            "Hôtel Saïss",
                            "Dar Talaa",
                            "Riad Nejjarine",
                            "Maison Zellige",
                            "Kasbah Mérinides",
                            "Hôtel Fès Jdid")),
            new City(
                    "Chefchaouen",
                    35.1688,
                    -5.2636,
                    new BigDecimal("0.80"),
                    List.of(
                            "Dar Azraq",
                            "Riad Ras El Ma",
                            "Hôtel Outa Hammam",
                            "Maison Bleue",
                            "Dar Akchour",
                            "Riad Rif",
                            "Hôtel Jebel Kalaa",
                            "Dar Lilas",
                            "Casa Andaluz",
                            "Riad Sebbanine")),
            new City(
                    "Essaouira",
                    31.5085,
                    -9.7595,
                    new BigDecimal("0.95"),
                    List.of(
                            "Riad Mogador",
                            "Dar Alizé",
                            "Hôtel Skala",
                            "Maison Thuya",
                            "Riad Bab Marrakech",
                            "Dar Gnaoua",
                            "Hôtel Île Purpurine",
                            "Villa Diabat",
                            "Riad Argan",
                            "Dar Mellah")),
            new City(
                    "Agadir",
                    30.4278,
                    -9.5981,
                    new BigDecimal("1.10"),
                    List.of(
                            "Hôtel Baie Bleue",
                            "Résidence Souss",
                            "Hôtel Corniche",
                            "Villa Founty",
                            "Kasbah Oufella",
                            "Hôtel Anza",
                            "Riad Tamraght",
                            "Résidence Sonaba",
                            "Hôtel Atlantique Sud",
                            "Dar Imouran")),
            new City(
                    "Tanger",
                    35.7595,
                    -5.8340,
                    new BigDecimal("1.00"),
                    List.of(
                            "Hôtel Cap Spartel",
                            "Dar Kasbah Tanja",
                            "Riad Petit Socco",
                            "Villa Marshan",
                            "Hôtel Malabata",
                            "Dar Hercule",
                            "Maison Achakar",
                            "Hôtel Boulevard",
                            "Riad Bab Bhar",
                            "Résidence Détroit")));

    private static final List<Template> TEMPLATES = List.of(
            new Template("Chambre Double", 2, "1 double", 8, 650),
            new Template("Chambre Twin", 2, "2 single", 6, 600),
            new Template("Suite", 3, "1 king + 1 sofa bed", 3, 1400),
            new Template("Chambre Familiale", 4, "1 double + 2 single", 4, 1100));

    private static final List<String> AMENITIES = List.of(
            "breakfast",
            "air-conditioning",
            "rooftop-terrace",
            "parking",
            "restaurant",
            "hammam",
            "pool",
            "spa",
            "airport-shuttle");

    private SeedCatalog() {}

    static List<SeedHotel> hotels() {
        List<SeedHotel> hotels = new ArrayList<>();
        for (City city : CITIES) {
            for (int i = 0; i < city.hotels().size(); i++) {
                hotels.add(hotel(city, i));
            }
        }
        return List.copyOf(hotels);
    }

    private static SeedHotel hotel(City city, int i) {
        String name = city.hotels().get(i);
        int stars = 3 + (i % 3);
        boolean medina = name.startsWith("Riad") || name.startsWith("Dar") || name.startsWith("Maison");
        String address = (medina ? "Médina, " : "Centre-ville, ") + city.name();

        List<String> amenities = new ArrayList<>();
        amenities.add("wifi");
        for (int a = 0; a < stars; a++) {
            amenities.add(AMENITIES.get((i + a) % AMENITIES.size()));
        }

        // A 5 x 2 grid of points around the city centre, about 500 m apart.
        BigDecimal lat = coordinate(city.lat() + ((i % 5) - 2) * 0.0045);
        BigDecimal lon = coordinate(city.lon() + ((i / 5) * 2 - 1) * 0.0040);

        BigDecimal starFactor = new BigDecimal(new String[] {"1.00", "1.40", "2.00"}[stars - 3]);
        List<SeedRoomType> roomTypes = new ArrayList<>();
        int count = 2 + ((i + 1) % 3); // 3,4,2,3,4,2,3,4,2,3: 30 per city
        for (int t = 0; t < count; t++) {
            Template tpl = TEMPLATES.get(t);
            BigDecimal price = BigDecimal.valueOf(tpl.basePriceMad())
                    .multiply(starFactor)
                    .multiply(city.priceFactor())
                    .setScale(0, RoundingMode.HALF_UP)
                    .setScale(2);
            int rooms = tpl.rooms() + (i % 3) - 1;
            boolean refundable = (i + t) % 5 != 0;
            roomTypes.add(new SeedRoomType(tpl.name(), tpl.capacity(), tpl.bedConfig(), rooms, price, refundable));
        }
        return new SeedHotel(
                name, city.name(), address, stars, List.copyOf(amenities), lat, lon, List.copyOf(roomTypes));
    }

    private static BigDecimal coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
