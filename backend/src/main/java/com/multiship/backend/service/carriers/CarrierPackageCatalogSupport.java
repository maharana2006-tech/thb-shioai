package com.multiship.backend.service.carriers;

import com.multiship.backend.model.CarrierPackageCatalog;

import java.util.List;
import java.util.Locale;

/**
 * Maps {@link CarrierPackageCatalog} rows (the DB-backed replacement for
 * each connector's old hardcoded {@code List.of(PackageOffering...)}) to
 * {@link CarrierConnector.PackageOffering}, applying the same US/PR-origin
 * gate the hardcoded FedEx One Rate check used to apply inline.
 */
final class CarrierPackageCatalogSupport {

    private CarrierPackageCatalogSupport() {
    }

    static boolean isUsOrPr(String originCountry) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        return "US".equals(o) || "PR".equals(o);
    }

    static List<CarrierConnector.PackageOffering> toOfferings(List<CarrierPackageCatalog> rows, String originCountry) {
        boolean usOrPr = isUsOrPr(originCountry);
        return rows.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getUsDomesticOnly()) || usOrPr)
                .map(r -> new CarrierConnector.PackageOffering(
                        r.getCode(), r.getName(), r.getLength(), r.getWidth(), r.getHeight(),
                        r.getMaxWeight(), Boolean.TRUE.equals(r.getFlatRate()), r.getScope()))
                .toList();
    }
}
