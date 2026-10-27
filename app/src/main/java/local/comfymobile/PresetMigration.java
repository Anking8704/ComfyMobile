package local.comfymobile;

public final class PresetMigration {
    public static String translate(String value){
        switch(value){
            case "画质 / 高细节":return "画质 / highly detailed";
            case "光影 / 柔和光线":return "光影 / soft lighting";
            case "光影 / 电影光影":return "光影 / cinematic lighting";
            case "人像 / 银白长发，白色礼裙，月光花园，柔和光线":return "人像 / A portrait of an adult woman with long silver hair, wearing a white evening dress in a moonlit garden, soft lighting.";
            default:return value;
        }
    }
}
