package local.comfymobile;
public class PresetMigrationTest {
    public static void main(String[] args){
        eq("画质 / highly detailed",PresetMigration.translate("画质 / 高细节"));
        eq("光影 / soft lighting",PresetMigration.translate("光影 / 柔和光线"));
        eq("光影 / cinematic lighting",PresetMigration.translate("光影 / 电影光影"));
        eq("我的角色 / 银发，红眼",PresetMigration.translate("我的角色 / 银发，红眼"));
        eq("my custom text",PresetMigration.translate("my custom text"));
        String template=PresetMigration.translate("人像 / 银白长发，白色礼裙，月光花园，柔和光线");
        if(!template.startsWith("人像 / ")||!template.substring(5).matches("[\\x20-\\x7E]+"))throw new AssertionError(template);
        System.out.println("PresetMigration: shipped defaults translated; user content preserved");
    }
    static void eq(String a,String b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
}
