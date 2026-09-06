package waltomadaam2.xinbot.playermonitor;

record IdentityResolution(
        String serverUuid,
        String offlineUuid,
        Lookup mojang,
        Lookup thirdParty,
        PlayerIdentityType identityType,
        boolean successful) {

    enum LookupStatus {
        FOUND,
        NOT_FOUND,
        ERROR,
        NOT_CHECKED
    }

    record Lookup(LookupStatus status, String uuid, boolean cached) {
        static Lookup found(String uuid) {
            return new Lookup(LookupStatus.FOUND, uuid, false);
        }

        static Lookup notFound() {
            return new Lookup(LookupStatus.NOT_FOUND, null, false);
        }

        static Lookup error() {
            return new Lookup(LookupStatus.ERROR, null, false);
        }

        static Lookup notChecked() {
            return new Lookup(LookupStatus.NOT_CHECKED, null, false);
        }

        static Lookup cached(String uuid) {
            return new Lookup(uuid == null ? LookupStatus.NOT_FOUND : LookupStatus.FOUND, uuid, true);
        }

        boolean completed() {
            return status == LookupStatus.FOUND || status == LookupStatus.NOT_FOUND;
        }
    }
}
