package com.aurora.studio.extraction;

import java.nio.file.Path;
import java.util.List;

final class SyntheticLegacyEstate {
  private SyntheticLegacyEstate() {}

  static List<Artifact> artifacts(StructuralParser parser) {
    return List.of(
        parser.artifact(
            Path.of("legacy/models/loyalty-tenure.sql"),
            "DATA_ASSET",
            "loyalty-tenure",
            "CREATE TABLE loyalty_tenure (guest_id uuid, tenure_months integer);"),
        parser.artifact(
            Path.of("legacy/models/loyalty-tenure-spec.md"),
            "STANDARD",
            "loyalty-tenure-spec",
            "The loyalty-tenure specification defines tenure_months as completed calendar years."),
        parser.artifact(
            Path.of("legacy/models/guest-value.yaml"),
            "FEATURE",
            "guest-value",
            "name: guest-value\ninputs: bookings, stays\nGuest-value is a legacy hotel feature."),
        parser.artifact(
            Path.of("legacy/models/guest-value-v2.yaml"),
            "FEATURE",
            "guest-value-v2",
            "name: guest-value-v2\ninputs: bookings, stays\nGuest-value-v2 is a near-duplicate legacy hotel feature."),
        parser.artifact(
            Path.of("legacy/models/booking-propensity.yaml"),
            "MODEL",
            "booking-propensity",
            "name: booking-propensity\nBooking-propensity predicts booking intent for a guest."),
        parser.artifact(
            Path.of("legacy/models/booking-likelihood.yaml"),
            "MODEL",
            "booking-likelihood",
            "name: booking-likelihood\nBooking-likelihood predicts booking intent for a guest."),
        parser.artifact(
            Path.of("legacy/docs/orphan-model.md"),
            "MODEL",
            "orphan-model",
            "The orphan-model is documented but has no implementation."),
        parser.artifact(
            Path.of("legacy/docs/retention-policy.md"),
            "STANDARD",
            "retention-policy",
            "The retention-policy is documented only in this document and governs guest history."));
  }
}
