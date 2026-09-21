package app.exteraless.speech;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class VoskModels {

    private static final List<VoskModel> MODELS = Collections.unmodifiableList(Arrays.asList(
            new VoskModel("ru", "Russian", "vosk-model-small-ru-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 46236750L, "d1759dc83eb8fd87850129afbd9f4b7b"),
            new VoskModel("en-us", "US English", "vosk-model-small-en-us-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 41205931L, "09ab50ccd62b674cbaa231b825f9c1cb"),
            new VoskModel("ua", "Ukrainian", "vosk-model-small-uk-v3-small", "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip", 143914407L, "3188e41b4a38369d8b9527d0149616ab"),
            new VoskModel("kz", "Kazakh", "vosk-model-small-kz-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.42.zip", 59697294L, "1c7dc487ac5e0c24acfa7566c106ba2d"),
            new VoskModel("uz", "Uzbek", "vosk-model-small-uz-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip", 51061189L, "4d75acfef76fe919c8fb68cd1179c182"),
            new VoskModel("ky", "Kyrgyz", "vosk-model-small-ky-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-ky-0.42.zip", 51041096L, "541eb45589c2b6f8350905086a76f072"),
            new VoskModel("tg", "Tajik", "vosk-model-small-tg-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-tg-0.22.zip", 51879043L, "fca4e40de915a5bd2f30e32d60af45c0"),
            new VoskModel("ar", "Arabic", "vosk-model-small-ar-0.3", "https://alphacephei.com/vosk/models/vosk-model-small-ar-0.3.zip", 104351896L, "0eb578c0779f85ef039b7ee5bc1b64ed"),
            new VoskModel("ar-tn", "Arabic Tunisian", "vosk-model-small-ar-tn-0.1-linto", "https://alphacephei.com/vosk/models/vosk-model-small-ar-tn-0.1-linto.zip", 165703754L, "67bbe16beaa17e6cf14f8c295ee5ddd1"),
            new VoskModel("ca", "Catalan", "vosk-model-small-ca-0.4", "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip", 43405881L, "5dc7901815fef3dc8b784b31f309385c"),
            new VoskModel("cn", "Chinese", "vosk-model-small-cn-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 43898754L, "d1a8e82933dcc7632667c036b0bb3dbb"),
            new VoskModel("cs", "Czech", "vosk-model-small-cs-0.4-rhasspy", "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip", 46088666L, "df5ddac5ee8632f6b46a6fc751953286"),
            new VoskModel("nl", "Dutch", "vosk-model-small-nl-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip", 40441176L, "4582e1f20d6849099da08511f9797017"),
            new VoskModel("eo", "Esperanto", "vosk-model-small-eo-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-eo-0.42.zip", 43839401L, "3e9319ca789fa06fd3efa68d51c85d6b"),
            new VoskModel("fa", "Farsi", "vosk-model-small-fa-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip", 53431220L, "cc2b18af256ffab2c44055f6a02ecb3d"),
            new VoskModel("fr", "French", "vosk-model-small-fr-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 42233323L, "8873b1234503f6edd55f54bfff31cf3e"),
            new VoskModel("ka", "Georgian", "vosk-model-small-ka-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-ka-0.42.zip", 45682310L, "7e95b6a69b60a5f1c45e29d1cd2ac353"),
            new VoskModel("de", "German", "vosk-model-small-de-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 46499967L, "4f21f92c0897b48287ef8839420608eb"),
            new VoskModel("gu", "Gujarati", "vosk-model-small-gu-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip", 108054987L, "4595a6f0cc0c88fee6eec7d80496fc10"),
            new VoskModel("hi", "Hindi", "vosk-model-small-hi-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 44458845L, "80f1265262c9a8a515f2707498e1b485"),
            new VoskModel("en-in", "Indian English", "vosk-model-small-en-in-0.4", "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip", 37573330L, "62fd085f33c8c5fc01a235030e2a1fe3"),
            new VoskModel("it", "Italian", "vosk-model-small-it-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 49665141L, "fbd8f9c72cbb8c3dfa3e4581bd3585f4"),
            new VoskModel("ja", "Japanese", "vosk-model-small-ja-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 49704573L, "0e3163dd62dfb0d823353718ac3cbf79"),
            new VoskModel("ko", "Korean", "vosk-model-small-ko-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 86914329L, "fa8029a173787a159e0e72fe6135f890"),
            new VoskModel("pl", "Polish", "vosk-model-small-pl-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 52979372L, "91cbbd6231320467da672be31827b6ac"),
            new VoskModel("pt", "Portuguese", "vosk-model-small-pt-0.3", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 32453112L, "458c69371c5a0b9ab6ee8fa417bf89da"),
            new VoskModel("es", "Spanish", "vosk-model-small-es-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39817833L, "2d5c94f9859a84881a0ef744738ebd31"),
            new VoskModel("sv", "Swedish", "vosk-model-small-sv-rhasspy-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-sv-rhasspy-0.15.zip", 303504931L, "5ae431c65fe8636692118792daa22ecc"),
            new VoskModel("te", "Telugu", "vosk-model-small-te-0.42", "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip", 60544249L, "ae3c093fd7dd883d462983b15900ca61"),
            new VoskModel("tr", "Turkish", "vosk-model-small-tr-0.3", "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip", 36855784L, "198511631860597639a0ebf779263fcf"),
            new VoskModel("en-gb", "UK English", "vosk-model-small-en-gb-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-en-gb-0.15.zip", 42757500L, "6afd611b04b2b47c129c3615dc502383"),
            new VoskModel("vn", "Vietnamese", "vosk-model-small-vn-0.4", "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", 33656337L, "b31b474a1ef75488c5fa575f8e2a1269")
    ));

    private VoskModels() {
    }

    public static List<VoskModel> all() {
        return MODELS;
    }

    public static VoskModel byCode(String code) {
        if (code == null) {
            return null;
        }
        for (VoskModel model : MODELS) {
            if (model.code.equals(code)) {
                return model;
            }
        }
        return null;
    }

    public static VoskModel forCurrentLocale(String language, String country) {
        if (language == null) {
            return null;
        }
        String lowerLanguage = language.toLowerCase();
        String full = country == null || country.isEmpty()
                ? lowerLanguage : lowerLanguage + "-" + country.toLowerCase();
        VoskModel exact = byCode(full);
        if (exact != null) {
            return exact;
        }
        if ("uk".equals(lowerLanguage)) {
            return byCode("ua");
        }
        if ("zh".equals(lowerLanguage)) {
            return byCode("cn");
        }
        if ("vi".equals(lowerLanguage)) {
            return byCode("vn");
        }
        if ("en".equals(lowerLanguage)) {
            return byCode("en-us");
        }
        return byCode(lowerLanguage);
    }
}
