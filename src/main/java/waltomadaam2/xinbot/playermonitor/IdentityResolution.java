package waltomadaam2.xinbot.playermonitor;

record IdentityResolution(
        String serverUuid,
        String offlineUuid,
        Lookup mojang,
        Lookup thirdParty,
        IdentityType identityType,
        boolean successful) {

    enum LookupStatus {
        FOUND,
        NOT_FOUND,
        ERROR,
        NOT_CHECKED
    }

    record Lookup(LookupStatus status, String uuid) {
        static Lookup found(String uuid) {
            return new Lookup(LookupStatus.FOUND, uuid);
        }

        static Lookup notFound() {
            return new Lookup(LookupStatus.NOT_FOUND, null);
        }

        static Lookup error() {
            return new Lookup(LookupStatus.ERROR, null);
        }

        static Lookup notChecked() {
            return new Lookup(LookupStatus.NOT_CHECKED, null);
        }

        boolean completed() {
            return status == LookupStatus.FOUND || status == LookupStatus.NOT_FOUND;
        }
    }
}
