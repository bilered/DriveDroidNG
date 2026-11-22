package com.pocketboot.app

import android.content.Context
import java.io.File

enum class VirtualEditionTarget(
    val editionId: String,
    val displayTitleWin11: String,
    val displayTitleWin10: String,
    val requiredBaseEdition: String,
    val minBuild: Int,
    val productId: String,
    val pfn: String,
    val contentId: String,
    val printNoCompatDriver: Int,
    val allowInsecureGuestAuth: Int,
    val dpHex: String,
    val dp4HeadHex: String,   // offset 0x0000 (112 bytes -> 0x000..0x06F)
    val dp4GuidHex: String,   // offset 0x0088 (72 bytes -> 0x088..0x0CF)
    val dp4EdHex: String,     // offset 0x0118 (UTF-16LE edition name)
    val dp4MidHex: String,    // offset 0x0328 (112 bytes -> 0x328..0x397)
    val dp4ChanHex: String,   // offset 0x03F8 (UTF-16LE channel e.g. Volume:GVLK / Retail / OEM:DM)
    val dp4SubHex: String     // offset 0x0478 (UTF-16LE sub-channel e.g. Volume / Retail / OEM)
) {
    ENTERPRISE(
        editionId = "Enterprise",
        displayTitleWin11 = "Windows 11 Enterprise",
        displayTitleWin10 = "Windows 10 Enterprise",
        requiredBaseEdition = "Professional",
        minBuild = 17063,
        productId = "00329-00000-00003-AA163",
        pfn = "Microsoft.Windows.4.X19-98698_8wekyb3d8bbwe",
        contentId = "05ce649a-eed1-d14e-aa01-4045f35ca54d",
        printNoCompatDriver = 1,
        allowInsecureGuestAuth = 0,
        dpHex = "A40000000300000030303332392D30303030302D30303030332D414131363300DA0C00005B54485D5831392D3938363938000000DA0C30000000186367E01565BE190800000000006A3CC65C7B0355CF03000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001820E0CF",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003200390030002D003000300030002D003000300030003000300033002D00300033002D0031003000320035002D0039003200300030002E0030003000300030002D003100310038003200300031003900",
        dp4GuidHex = "370033003100310031003100320031002D0035003600330038002D0034003000660036002D0062006300310031002D00660031006400370062003000640036003400330030003000",
        dp4EdHex = "45006E0074006500720070007200690073006500",
        dp4MidHex = "DA0C30000000186367E01565BE1908002F804AB2DB805EA13D7F55FE45580AACE318F8564D3FADB7415C2F5B04D68038B0CB8213C664AB77BE2BCA373C415436BC9DCF06233D14E08772FC1D4C4137145B00540048005D005800310039002D0039003800360039003800",
        dp4ChanHex = "56006F006C0075006D0065003A00470056004C004B00",
        dp4SubHex = "56006F006C0075006D006500"
    ),
    ENTERPRISE_N(
        editionId = "EnterpriseN",
        displayTitleWin11 = "Windows 11 Enterprise N",
        displayTitleWin10 = "Windows 10 Enterprise N",
        requiredBaseEdition = "ProfessionalN",
        minBuild = 17063,
        productId = "00329-90000-00000-AA065",
        pfn = "Microsoft.Windows.27.X19-98747_8wekyb3d8bbwe",
        contentId = "95bd2561-e54d-b969-789e-f7d12b386c67",
        printNoCompatDriver = 1,
        allowInsecureGuestAuth = 0,
        dpHex = "A40000000300000030303332392D39303030302D30303030302D414130363500E30C00005B54485D5831392D3938373437000000E30C0000000074483162F763DE2D090000000000B168C75CB909475F0300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000BBAF583E",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003200390039002D003000300030002D003000300030003000300030002D00300033002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "650032003700320065003300650032002D0037003300320066002D0034006300360035002D0061003800660030002D00340038003400370034003700640030006400390034003700",
        dp4EdHex = "45006E00740065007200700072006900730065004E00",
        dp4MidHex = "E30C0000000074483162F763DE2D0900013ACB6CFB7A43817B1886F4231F31CB9D9984E412F00E88D4AFC9BC29D6805A013BC42A03714918ED6F587A2424253F18375D7E42711D3553A7EF7BDAACB9B05B00540048005D005800310039002D0039003800370034003700",
        dp4ChanHex = "56006F006C0075006D0065003A00470056004C004B00",
        dp4SubHex = "56006F006C0075006D006500"
    ),
    EDUCATION(
        editionId = "Education",
        displayTitleWin11 = "Windows 11 Education",
        displayTitleWin10 = "Windows 10 Education",
        requiredBaseEdition = "Professional",
        minBuild = 17063,
        productId = "00328-10000-00001-AA343",
        pfn = "Microsoft.Windows.121.X19-98668_8wekyb3d8bbwe",
        contentId = "ce14a187-835c-7270-6fcb-602268e16063",
        printNoCompatDriver = 1,
        allowInsecureGuestAuth = 0,
        dpHex = "A40000000300000030303332382D31303030302D30303030312D414133343300D10C00005B54485D5831392D3938363638000000D10C10000000603EF693CD84A6280800000000003B3EC65CEFDEBD170300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000FBB56065",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003200380031002D003000300030002D003000300030003000300031002D00300033002D0031003000320035002D0039003200300030002E0030003000300030002D003100310038003200300031003900",
        dp4GuidHex = "650030006300340032003200380038002D0039003800300063002D0034003700380038002D0061003000310034002D00630030003800300064003200650031003900320036006500",
        dp4EdHex = "45006400750063006100740069006F006E00",
        dp4MidHex = "D10C10000000603EF693CD84A628080097DF6CB8F7B0FE32CAD1D565E037D8EF8A2DD512CE70153460A140266ECEED293F9366CBE687D3DB1F6A8C152BBF06D2CF3526E15637F4CAD048E4FECFC139EC5B00540048005D005800310039002D0039003800360036003800",
        dp4ChanHex = "56006F006C0075006D0065003A00470056004C004B00",
        dp4SubHex = "56006F006C0075006D006500"
    ),
    EDUCATION_N(
        editionId = "EducationN",
        displayTitleWin11 = "Windows 11 Education N",
        displayTitleWin10 = "Windows 10 Education N",
        requiredBaseEdition = "ProfessionalN",
        minBuild = 17063,
        productId = "00328-60000-00001-AA362",
        pfn = "Microsoft.Windows.122.X19-98682_8wekyb3d8bbwe",
        contentId = "70f4ccac-9a50-0c5b-9b8c-4fe0d6276cf1",
        printNoCompatDriver = 1,
        allowInsecureGuestAuth = 0,
        dpHex = "A40000000300000030303332382D36303030302D30303030312D414133363200D60C00005B54485D5831392D3938363832000000D60C10000000FC97E3F1CCDF3C370900000000004C69C75C1AA7E14A03000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002B44EB37",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003200380036002D003000300030002D003000300030003000300031002D00300033002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "330063003100300032003300350035002D0064003000320037002D0034003200630036002D0061006400320033002D00320065003700650066003800610030003200350038003500",
        dp4EdHex = "45006400750063006100740069006F006E004E00",
        dp4MidHex = "D60C10000000FC97E3F1CCDF3C370900CDF1B541541FBEFA02F6D41F9D7F8F41203AAADAE64A2981E617D6F4E3439F1592B7C251D0024D513741D883801A4F2010CE63E9B65244FB1D641A4D8963A50E5B00540048005D005800310039002D0039003800360038003200",
        dp4ChanHex = "56006F006C0075006D0065003A00470056004C004B00",
        dp4SubHex = "56006F006C0075006D006500"
    ),
    PRO_EDUCATION(
        editionId = "ProfessionalEducation",
        displayTitleWin11 = "Windows 11 Pro Education",
        displayTitleWin10 = "Windows 10 Pro Education",
        requiredBaseEdition = "Professional",
        minBuild = 17063,
        productId = "00380-00000-00001-AA261",
        pfn = "Microsoft.Windows.164.X21-04955_8wekyb3d8bbwe",
        contentId = "3c88328a-7c1e-aa8a-72e7-edca5665b405",
        printNoCompatDriver = 0,
        allowInsecureGuestAuth = 1,
        dpHex = "A40000000300000030303338302D30303030302D30303030312D414132363100D80E00005B5253315D5832312D30343935350000D80E10000000B0D59918A63DEAC7090000000000F83EC65C8D0D82E800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003A8EA582",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003800300030002D003000300030002D003000300030003000300031002D00300030002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "360032006600300063003100300030002D0039006300350033002D0034006500300032002D0062003800380036002D00610033003500320038006400640066006500370066003600",
        dp4EdHex = "500072006F00660065007300730069006F006E0061006C0045006400750063006100740069006F006E00",
        dp4MidHex = "D80E10000000B0D59918A63DEAC7090049CE26E216D5DA7CB7BCE6C212337CFD8DFFB4DF3542F3082038118C22F8F62AEB533B180E8C2EB103A53ADA9E058D80FE788F03A557C608FA26AF208C838F0B5B005200530031005D005800320031002D0030003400390035003500",
        dp4ChanHex = "520065007400610069006C00",
        dp4SubHex = "520065007400610069006C00"
    ),
    PRO_EDUCATION_N(
        editionId = "ProfessionalEducationN",
        displayTitleWin11 = "Windows 11 Pro Education N",
        displayTitleWin10 = "Windows 10 Pro Education N",
        requiredBaseEdition = "ProfessionalN",
        minBuild = 17063,
        productId = "00380-10000-00001-AA148",
        pfn = "Microsoft.Windows.165.X21-04956_8wekyb3d8bbwe",
        contentId = "3dc4d427-0e39-c53a-6b2c-a801a01f902b",
        printNoCompatDriver = 0,
        allowInsecureGuestAuth = 1,
        dpHex = "A40000000300000030303338302D31303030302D30303030312D414131343800D90E00005B5253315D5832312D30343935360000D90E1000000040E86D4ECFECBC12090000000000826BC75C947D897B0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000359333A5",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003800300031002D003000300030002D003000300030003000300031002D00300030002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "310033006100330038003600390038002D0034006100340039002D0034006200390065002D0038006500380033002D00390038006600650035003100310031003000390035003300",
        dp4EdHex = "500072006F00660065007300730069006F006E0061006C0045006400750063006100740069006F006E004E00",
        dp4MidHex = "D90E1000000040E86D4ECFECBC12090004D5AD09CB250227EA46806F10551398C0BE8348C85D5056A4D7FDDC3D059E056747155A62192E948AA6815878A1A99C2C20655DFBB248ACDBD8A73DCC9979BB5B005200530031005D005800320031002D0030003400390035003600",
        dp4ChanHex = "520065007400610069006C00",
        dp4SubHex = "520065007400610069006C00"
    ),
    PRO_WORKSTATION(
        editionId = "ProfessionalWorkstation",
        displayTitleWin11 = "Windows 11 Pro for Workstations",
        displayTitleWin10 = "Windows 10 Pro for Workstations",
        requiredBaseEdition = "Professional",
        minBuild = 17063,
        productId = "00391-70000-00000-AA825",
        pfn = "Microsoft.Windows.161.X21-43626_8wekyb3d8bbwe",
        contentId = "665f6f21-1692-5d08-17e4-934e0c638268",
        printNoCompatDriver = 0,
        allowInsecureGuestAuth = 1,
        dpHex = "A40000000300000030303339312D37303030302D30303030302D4141383235004D0F00005B5253335D5832312D343336323600004D0F00000000344DD4F276BB0150090000000000583FC65CE95A1CEA00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000007DD61CA5",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003900310037002D003000300030002D003000300030003000300030002D00300030002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "650062003600640033003400360066002D0031006300360030002D0034003600340033002D0062003900360030002D00340030006500630033003100350039003600630034003500",
        dp4EdHex = "500072006F00660065007300730069006F006E0061006C0057006F0072006B00730074006100740069006F006E00",
        dp4MidHex = "4D0F00000000344DD4F276BB01500900DCA02A65542EDA7278AEE106EB6DBB25C6E2B84961296DB9FCF4F525C29FD0B4434023AD5574B64901101BC377CDE0346F5E7F97ED597050592F15D40B2F63C85B005200530033005D005800320031002D0034003300360032003600",
        dp4ChanHex = "520065007400610069006C00",
        dp4SubHex = "520065007400610069006C00"
    ),
    PRO_WORKSTATION_N(
        editionId = "ProfessionalWorkstationN",
        displayTitleWin11 = "Windows 11 Pro for Workstations N",
        displayTitleWin10 = "Windows 10 Pro for Workstations N",
        requiredBaseEdition = "ProfessionalN",
        minBuild = 17063,
        productId = "00392-20000-00000-AA717",
        pfn = "Microsoft.Windows.162.X21-43644_8wekyb3d8bbwe",
        contentId = "0ec42cc5-2b09-a734-1bb9-ff00e4a52d46",
        printNoCompatDriver = 0,
        allowInsecureGuestAuth = 1,
        dpHex = "A40000000300000030303339322D32303030302D30303030302D414137313700520F00005B5253335D5832312D34333634340000520F00000000D8B84CE6055D81ED080000000000CA6BC75C6DA8F63B0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000619D9E2C",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003900320032002D003000300030002D003000300030003000300030002D00300030002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "380039006500380037003500310030002D0062006100390032002D0034003500660036002D0038003300320039002D00330061006600610039003000350065003300650038003300",
        dp4EdHex = "500072006F00660065007300730069006F006E0061006C0057006F0072006B00730074006100740069006F006E004E00",
        dp4MidHex = "520F00000000D8B84CE6055D81ED0800088943EDD3CF7A8B08D896A5563ED4A52421E0D01861CB786C78E3CA17454B8912A965EB02684AC4AD00913D7997FA5432771B7FCD710600DE39FE61521B10F45B005200530033005D005800320031002D0034003300360034003400",
        dp4ChanHex = "520065007400610069006C00",
        dp4SubHex = "520065007400610069006C00"
    ),
    IOT_ENTERPRISE(
        editionId = "IoTEnterprise",
        displayTitleWin11 = "Windows 11 IoT Enterprise",
        displayTitleWin10 = "Windows 10 IoT Enterprise",
        requiredBaseEdition = "Professional",
        minBuild = 18277,
        productId = "00436-20000-00000-AAOEM",
        pfn = "Microsoft.Windows.188.X21-99378_8wekyb3d8bbwe",
        contentId = "4b1412af-12ad-0bbd-177e-6f7579c8600f",
        printNoCompatDriver = 1,
        allowInsecureGuestAuth = 0,
        dpHex = "A40000000300000030303433362D32303030302D30303030302D41414F454D000A1100005B313948315D5832312D3939333738000A1100000000C8BFC44ABE281573090000000000BA3FC65C79FBCEC902000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004E6597C1",
        dp4HeadHex = "F804000004000000350035003000340031002D00300034003300360032002D003000300030002D003000300030003000300030002D00300032002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "380061006200390062006400640031002D0031006600360037002D0034003900390037002D0038003200640039002D00380038003700380035003200300038003300370064003900",
        dp4EdHex = "49006F00540045006E0074006500720070007200690073006500",
        dp4MidHex = "0A1100000000C8BFC44ABE2815730900C2586D4B3D14127A7AA589B597436B2B668BB57F03BBF64B34DADF82166ED03DAE567D1A43D2A5F01CBEFFA92C8D86415273F040C17EFE9C1A53F6D49DB0A54B5B0031003900480031005D005800320031002D0039003900330037003800",
        dp4ChanHex = "4F0045004D003A0044004D00",
        dp4SubHex = "4F0045004D00"
    ),
    SERVER_RDSH(
        editionId = "ServerRdsh",
        displayTitleWin11 = "Windows 11 Enterprise multi-session",
        displayTitleWin10 = "Windows 10 Enterprise multi-session",
        requiredBaseEdition = "Professional",
        minBuild = 17063,
        productId = "00432-70000-00001-AA701",
        pfn = "Microsoft.Windows.175.X21-83765_8wekyb3d8bbwe",
        contentId = "8e20e60b-0826-3084-51fe-cda9e1b184cd",
        printNoCompatDriver = 1,
        allowInsecureGuestAuth = 0,
        dpHex = "A40000000300000030303433322D37303030302D30303030312D414137303100E71000005B5253355D5832312D38333736350000E710100000000019E6ABC946E0F0090000000000A23EC65C05F0E66F0300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000651DE578",
        dp4HeadHex = "F804000004000000350035003000340031002D00300034003300320037002D003000300030002D003000300030003000300031002D00300033002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "650063003800360038006500360035002D0066006100640066002D0034003700350039002D0062003200330065002D00390033006600650033003700660032006300630032003900",
        dp4EdHex = "5300650072007600650072005200640073006800",
        dp4MidHex = "E710100000000019E6ABC946E0F009002B11469E49D838FE3D68708FF122CB51C5BB3CB181B914B08DE8BFDDE9D3B495B852E47C048CEF48B76AA54F7352659895F9AA7B24F08092E3CA900D5B3DE7A15B005200530035005D005800320031002D0038003300370036003500",
        dp4ChanHex = "56006F006C0075006D0065003A00470056004C004B00",
        dp4SubHex = "56006F006C0075006D006500"
    ),
    CLOUD_EDITION(
        editionId = "CloudEdition",
        displayTitleWin11 = "Windows 11 SE",
        displayTitleWin10 = "Windows 10 SE",
        requiredBaseEdition = "Professional",
        minBuild = 21364,
        productId = "00475-80000-00000-AA899",
        pfn = "Microsoft.Windows.203.X22-53847_8wekyb3d8bbwe",
        contentId = "cc260727-8d42-5c4c-3c2f-f093008dcd9a",
        printNoCompatDriver = 0,
        allowInsecureGuestAuth = 1,
        dpHex = "A40000000300000030303437352D38303030302D30303030302D414138393900961200005B436F5D5832322D3533383437000000961200000000E02F4E1CFB11241C09000000000060D886607B382CA600000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000007577B471",
        dp4HeadHex = "F804000004000000350035003000340031002D00300034003700350038002D003000300030002D003000300030003000300030002D00300030002D0031003000330033002D00310039003000340032002E0030003000300030002D003100310036003200300032003100",
        dp4GuidHex = "390032006600620038003700320036002D0039003200610038002D0034006600660063002D0039003400630065002D00660038003200650030003700340034003400360035003300",
        dp4EdHex = "43006C006F0075006400450064006900740069006F006E00",
        dp4MidHex = "961200000000E02F4E1CFB11241C0900636972E5F0D63C608BA93E4720664C3858B991E1CC7A277780E018E825B3AE1F30148C66F7E5B0BEF9DCA012F82B093D6350ED21B50AEBCA55A81CECD47AD94F5B0043006F005D005800320032002D0035003300380034003700",
        dp4ChanHex = "520065007400610069006C00",
        dp4SubHex = "520065007400610069006C00"
    ),
    CLOUD_EDITION_N(
        editionId = "CloudEditionN",
        displayTitleWin11 = "Windows 11 SE N",
        displayTitleWin10 = "Windows 10 SE N",
        requiredBaseEdition = "ProfessionalN",
        minBuild = 21364,
        productId = "00476-20000-00000-AA946",
        pfn = "Microsoft.Windows.202.X22-53884_8wekyb3d8bbwe",
        contentId = "c7cfb460-bc9b-b32a-69b3-acf52bd6fa48",
        printNoCompatDriver = 0,
        allowInsecureGuestAuth = 1,
        dpHex = "A40000000300000030303437362D32303030302D30303030302D4141393436009A1200005B436F5D5832322D35333838340000009A1200000000083F35E14BD1E61C090000000000DFD886600A6F758A0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000075CB374",
        dp4HeadHex = "F804000004000000350035003000340031002D00300034003700360032002D003000300030002D003000300030003000300030002D00300030002D0031003000330033002D00310039003000340032002E0030003000300030002D003100310036003200300032003100",
        dp4GuidHex = "640034006200640063003600370038002D0030006100340062002D0034006100330032002D0061003500620033002D00610061006100320034006300330062003000660032003400",
        dp4EdHex = "43006C006F0075006400450064006900740069006F006E004E00",
        dp4MidHex = "9A1200000000083F35E14BD1E61C0900FFC38A76E892BD57EADFADE6E936A55CE150993173EE18B032CE8E375692B16E46BE16AECC3DAEEDB01882E374A0EFF0DA5337243CED353B562C337E864DCCEA5B0043006F005D005800320032002D0035003300380038003400",
        dp4ChanHex = "520065007400610069006C00",
        dp4SubHex = "520065007400610069006C00"
    ),
    CORE_SINGLE_LANGUAGE(
        editionId = "CoreSingleLanguage",
        displayTitleWin11 = "Windows 11 Home Single Language",
        displayTitleWin10 = "Windows 10 Home Single Language",
        requiredBaseEdition = "Core",
        minBuild = 17063,
        productId = "00327-60000-00000-AA157",
        pfn = "Microsoft.Windows.100.X19-99661_8wekyb3d8bbwe",
        contentId = "6fba12a6-3077-5301-cfde-f22f59f1e2a6",
        printNoCompatDriver = 0,
        allowInsecureGuestAuth = 1,
        dpHex = "A40000000300000030303332372D36303030302D30303030302D414131353700CC0C00005B54485D5831392D3939363631000000CC0C00000000A0DB36D6DC41C8CD090000000000B270C75CC5F629D50000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100770A8",
        dp4HeadHex = "F804000004000000350035003000340031002D00300033003200370036002D003000300030002D003000300030003000300030002D00300030002D0031003000320035002D0039003200300030002E0030003000300030002D003100310039003200300031003900",
        dp4GuidHex = "330061006500320063006300310034002D0061006200320064002D0034003100660034002D0039003700320066002D00350065003200300031003400320037003700310064006300",
        dp4EdHex = "43006F0072006500530069006E0067006C0065004C0061006E0067007500610067006500",
        dp4MidHex = "CC0C00000000A0DB36D6DC41C8CD09002A9E0AB03607F36AE6CA609D0A54E68E58186B61A1D6A3C0BFDD6FC40AF2168D262581306BED0061DC0DDD8912031EE633FC7BB84B09C413ACAA627B3AABC92C5B00540048005D005800310039002D0039003900360036003100",
        dp4ChanHex = "520065007400610069006C00",
        dp4SubHex = "520065007400610069006C00"
    );

    fun displayNameForBuild(buildMajor: Int): String =
        if (buildMajor >= 21990) displayTitleWin11 else displayTitleWin10

    fun buildDp4Bytes(): ByteArray {
        val buf = ByteArray(1272)
        fun put(offset: Int, hex: String) {
            var i = 0
            while (i + 1 < hex.length) {
                buf[offset + (i / 2)] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
        }
        put(0x000, dp4HeadHex)
        put(0x088, dp4GuidHex)
        put(0x118, dp4EdHex)
        put(0x328, dp4MidHex)
        put(0x3F8, dp4ChanHex)
        put(0x478, dp4SubHex)
        return buf
    }

    fun buildDpBytes(): ByteArray {
        val buf = ByteArray(164)
        var i = 0
        while (i + 1 < dpHex.length && (i / 2) < buf.size) {
            buf[i / 2] = ((Character.digit(dpHex[i], 16) shl 4) + Character.digit(dpHex[i + 1], 16)).toByte()
            i += 2
        }
        return buf
    }

    companion object {
        fun fromEditionId(id: String): VirtualEditionTarget? =
            entries.firstOrNull { it.editionId.equals(id.trim(), ignoreCase = true) }

        fun availableForBaseEditions(baseEditionCodes: Collection<String>, buildMajor: Int): List<VirtualEditionTarget> {
            val normalized = baseEditionCodes.map { it.lowercase() }.toSet()
            return entries.filter { target ->
                target.requiredBaseEdition.lowercase() in normalized && (buildMajor == 0 || buildMajor >= target.minBuild)
            }
        }
    }
}

object VirtualEditionsPlugin {
    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun formatRegHexValue(bytes: ByteArray): String {
        val sb = StringBuilder()
        bytes.forEachIndexed { idx, b ->
            if (idx > 0) {
                sb.append(',')
                if (idx % 16 == 0) sb.append("\\\r\n  ")
            }
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** Generates standard REGEDIT4 ASCII patch file for the offline SOFTWARE hive. */
    fun buildSoftwareRegFile(target: VirtualEditionTarget): String {
        val dpHex = formatRegHexValue(target.buildDpBytes())
        val dp4Hex = formatRegHexValue(target.buildDp4Bytes())
        val printDword = String.format("%08x", target.printNoCompatDriver)
        return buildString {
            append("REGEDIT4\r\n\r\n")
            append("[HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion]\r\n")
            append("\"DigitalProductId\"=hex:$dpHex\r\n")
            append("\"DigitalProductId4\"=hex:$dp4Hex\r\n")
            append("\"ProductId\"=\"${target.productId}\"\r\n")
            append("\"EditionID\"=\"${target.editionId}\"\r\n\r\n")
            append("[HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\DefaultProductKey2]\r\n")
            append("\"ProductId\"=\"${target.productId}\"\r\n")
            append("\"EditionID\"=\"${target.editionId}\"\r\n")
            append("\"OSProductPfn\"=\"${target.pfn}\"\r\n")
            append("\"OSProductContentId\"=\"${target.contentId}\"\r\n")
            append("\"DigitalProductId\"=hex:$dpHex\r\n")
            append("\"DigitalProductId4\"=hex:$dp4Hex\r\n\r\n")
            append("[HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Print]\r\n")
            append("\"DoNotInstallCompatibleDriverFromWindowsUpdate\"=dword:$printDword\r\n\r\n")
        }
    }

    /** Generates standard REGEDIT4 ASCII patch file for the offline SYSTEM hive. */
    fun buildSystemRegFile(target: VirtualEditionTarget): String {
        val guestDword = String.format("%08x", target.allowInsecureGuestAuth)
        return buildString {
            append("REGEDIT4\r\n\r\n")
            append("[HKEY_LOCAL_MACHINE\\SYSTEM\\ControlSet001\\Control\\ProductOptions]\r\n")
            append("\"OSProductPfn\"=\"${target.pfn}\"\r\n")
            append("\"OSProductContentId\"=\"${target.contentId}\"\r\n\r\n")
            append("[HKEY_LOCAL_MACHINE\\SYSTEM\\ControlSet001\\Services\\LanmanWorkstation\\Parameters]\r\n")
            append("\"AllowInsecureGuestAuth\"=dword:$guestDword\r\n\r\n")
        }
    }

    /**
     * Clones a base edition inside a non-solid WIM (`install_stage.wim` / `install.wim`),
     * verifies the edition XML exists before cloning, patches SOFTWARE & SYSTEM hives cleanly
     * via `reged -C -I`, injects `<Edition>Edition.xml`, and sets WIM metadata properties.
     */
    fun createVirtualEditionInWim(
        context: Context,
        wimBin: String,
        regedBin: String,
        wimPath: String,
        target: VirtualEditionTarget,
        onLog: (String) -> Unit = {}
    ): Boolean {
        val softReg = File(context.cacheDir, "pb_ve_${target.editionId}_soft.reg")
        val sysReg = File(context.cacheDir, "pb_ve_${target.editionId}_sys.reg")
        softReg.writeText(buildSoftwareRegFile(target), Charsets.US_ASCII)
        sysReg.writeText(buildSystemRegFile(target), Charsets.US_ASCII)

        val qWim = shellQuote(wimBin)
        val qReg = shellQuote(regedBin)
        val qWimPath = shellQuote(wimPath)
        val qSoftReg = shellQuote(softReg.absolutePath)
        val qSysReg = shellQuote(sysReg.absolutePath)
        val edId = target.editionId
        val reqBase = target.requiredBaseEdition

        val script = """
            set -e
            VE_TMP="/data/local/tmp/pb_ve_$edId"
            rm -rf "${'$'}VE_TMP" && mkdir -p "${'$'}VE_TMP"
            cleanup() { rm -rf "${'$'}VE_TMP"; }
            trap cleanup EXIT

            TOTAL_IDX=${'$'}($qWim info $qWimPath 2>/dev/null | grep -c '^Index:' || echo 0)
            SRC_IDX=""
            ALREADY=0
            i=1
            while [ "${'$'}i" -le "${'$'}TOTAL_IDX" ]; do
                CUR_ED=${'$'}($qWim info $qWimPath "${'$'}i" 2>/dev/null | sed -n 's/^Edition ID:[[:space:]]*//p' | head -n 1)
                if [ "${'$'}CUR_ED" = "$edId" ]; then ALREADY=1; break; fi
                if [ -z "${'$'}SRC_IDX" ] && [ "${'$'}CUR_ED" = "$reqBase" ]; then SRC_IDX="${'$'}i"; fi
                i=${'$'}((i + 1))
            done

            if [ "${'$'}ALREADY" = "1" ]; then
                echo "VE_SKIP|Already exists: $edId"
                exit 0
            fi
            if [ -z "${'$'}SRC_IDX" ]; then
                echo "VE_SKIP|Base edition $reqBase not found in WIM for $edId"
                exit 0
            fi

            BUILD_NUM=${'$'}($qWim info $qWimPath "${'$'}SRC_IDX" 2>/dev/null | sed -n 's/^Build:[[:space:]]*//p' | head -n 1 | cut -d. -f1)
            [ -z "${'$'}BUILD_NUM" ] && BUILD_NUM=22000
            if [ "${'$'}BUILD_NUM" -lt ${target.minBuild} ]; then
                echo "VE_SKIP|$edId requires build ${target.minBuild}+ (found ${'$'}BUILD_NUM)"
                exit 0
            fi

            # Pre-check edition XML exists inside source index before cloning
            if ! $qWim extract $qWimPath "${'$'}SRC_IDX" "/Windows/servicing/Editions/${edId}Edition.xml" --dest-dir="${'$'}VE_TMP" --no-acls --no-attributes >/dev/null 2>&1; then
                echo "VE_SKIP|Build lacks /Windows/servicing/Editions/${edId}Edition.xml"
                exit 0
            fi
            if [ ! -f "${'$'}VE_TMP/${edId}Edition.xml" ]; then
                echo "VE_SKIP|Missing ${edId}Edition.xml after extract"
                exit 0
            fi

            if [ "${'$'}BUILD_NUM" -ge 21990 ]; then
                WIN_DESC=${shellQuote(target.displayTitleWin11)}
                WIN_NAME="Windows 11 $edId"
            else
                WIN_DESC=${shellQuote(target.displayTitleWin10)}
                WIN_NAME="Windows 10 $edId"
            fi

            # Clone base index inside WIM (near-zero size overhead due to WIM single-instancing)
            $qWim export $qWimPath "${'$'}SRC_IDX" $qWimPath "${'$'}WIN_NAME" >/dev/null 2>&1
            NEW_IDX=${'$'}($qWim info $qWimPath 2>/dev/null | grep -c '^Index:' || echo 0)

            $qWim extract $qWimPath "${'$'}NEW_IDX" "/Windows/System32/config/SOFTWARE" "/Windows/System32/config/SYSTEM" --dest-dir="${'$'}VE_TMP" --no-acls --no-attributes >/dev/null 2>&1
            SOFT_FILE=${'$'}(find "${'$'}VE_TMP" -maxdepth 1 -type f -iname 'software' | head -n 1)
            SYS_FILE=${'$'}(find "${'$'}VE_TMP" -maxdepth 1 -type f -iname 'system' | head -n 1)
            if [ -z "${'$'}SOFT_FILE" ] || [ -z "${'$'}SYS_FILE" ]; then
                echo "VE_ERR|Could not extract SOFTWARE/SYSTEM hives"
                exit 1
            fi

            # Safe per-hive REGEDIT4 import via bundled reged (exit 0 or 2 = success in chntpw/reged)
            set +e
            printf 'y\n' | $qReg -C -I "${'$'}SOFT_FILE" 'HKEY_LOCAL_MACHINE\SOFTWARE' $qSoftReg > "${'$'}VE_TMP/reg_soft.log" 2>&1
            RC_SOFT=${'$'}?
            printf 'y\n' | $qReg -C -I "${'$'}SYS_FILE" 'HKEY_LOCAL_MACHINE\SYSTEM' $qSysReg > "${'$'}VE_TMP/reg_sys.log" 2>&1
            RC_SYS=${'$'}?
            set -e
            if [ "${'$'}RC_SOFT" -ne 0 ] && [ "${'$'}RC_SOFT" -ne 2 ]; then
                echo "VE_ERR|reged SOFTWARE failed (rc=${'$'}RC_SOFT): ${'$'}(tail -n 2 "${'$'}VE_TMP/reg_soft.log" 2>/dev/null | tr '\n' ' ')"
                exit 1
            fi
            if [ "${'$'}RC_SYS" -ne 0 ] && [ "${'$'}RC_SYS" -ne 2 ]; then
                echo "VE_ERR|reged SYSTEM failed (rc=${'$'}RC_SYS): ${'$'}(tail -n 2 "${'$'}VE_TMP/reg_sys.log" 2>/dev/null | tr '\n' ' ')"
                exit 1
            fi

            UPD_CMD="${'$'}VE_TMP/upd.txt"
            printf "add '%s' /Windows/System32/config/SOFTWARE\nadd '%s' /Windows/System32/config/SYSTEM\nadd '%s' /Windows/%s.xml\n" \
                "${'$'}SOFT_FILE" "${'$'}SYS_FILE" "${'$'}VE_TMP/${edId}Edition.xml" "$edId" > "${'$'}UPD_CMD"
            $qWim update $qWimPath "${'$'}NEW_IDX" < "${'$'}UPD_CMD" >/dev/null 2>&1

            $qWim info $qWimPath "${'$'}NEW_IDX" \
                --image-property DESCRIPTION="${'$'}WIN_DESC" \
                --image-property DISPLAYNAME="${'$'}WIN_DESC" \
                --image-property DISPLAYDESCRIPTION="${'$'}WIN_DESC" \
                --image-property WINDOWS/EDITIONID="$edId" \
                --image-property FLAGS="$edId" >/dev/null 2>&1

            echo "VE_OK|Created virtual edition: ${'$'}WIN_DESC (index ${'$'}NEW_IDX)"
        """.trimIndent()

        return try {
            val res = WindowsBuildEngine.runIsolatedRootScript(context, "ve_$edId", script)
            val lines = res.out.filter { it.startsWith("VE_") }
            lines.forEach { l -> onLog(l.substringAfter('|')) }
            res.isSuccess && lines.any { it.startsWith("VE_OK|") }
        } finally {
            softReg.delete()
            sysReg.delete()
        }
    }
}
