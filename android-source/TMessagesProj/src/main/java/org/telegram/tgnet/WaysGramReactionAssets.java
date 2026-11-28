package org.telegram.tgnet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Документы реакций Telegram: рисунок и анимации по слотам.
 *
 * <p>Сгенерировано {@code scripts/waysgram_reaction_assets.py} из выгрузки
 * {@code /exportreactions} — не редактировать вручную. Идентификаторы документов настоящие
 * (телеграмные), поэтому штатный {@code AnimatedEmojiDrawable}/{@code RLottieDrawable} играет
 * официальные анимации: {@code appear/select/activate} — появление эмодзи, {@code effect} —
 * вспышка вокруг сообщения, {@code around} — частицы в чипе.</p>
 *
 * <p>Файлы лежат в {@code assets/waysgram_emoji/tg/} и раздаются транспортом через
 * {@code upload.getFile}: клиент качает их как обычный документ.</p>
 */
final class WaysGramReactionAssets {
    private static final int MAX_TGS_BYTES = 65536;

    /** Слоты в том порядке, в котором они лежат в {@code SLOT_DOC}. */
    static final String[] SLOTS = {"static_icon", "appear_animation", "select_animation", "activate_animation", "effect_animation", "around_animation", "center_icon"};

    static final String[] EMOJI = {
        "❤", "👍", "👎", "🔥", "🥰", "👏",
        "😁", "🤔", "🤯", "😱", "🤬", "😢",
        "🎉", "🤩", "🤮", "💩", "🙏", "👌",
        "🕊", "🤡", "🥱", "🥴", "😍", "🐳",
        "❤‍🔥", "🌚", "🌭", "💯", "🤣", "⚡",
        "🍌", "🏆", "💔", "🤨", "😐", "🍓",
        "🍾", "💋", "🖕", "😈", "😴", "😭",
        "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈",
        "😇", "😨", "🤝", "✍", "🤗", "🫡",
        "🎅", "🎄", "☃", "💅", "🤪", "🗿",
        "🆒", "💘", "🙉", "🦄", "😘", "💊",
        "🙊", "😎", "👾", "🤷‍♂", "🤷", "🤷‍♀",
        "😡", "😂",
    };

    static final String[] TITLE = {
        "Red Heart", "Thumbs Up", "Thumbs Down",
        "Fire", "Smiling Face with Hearts", "Clapping Hands",
        "Beaming Face", "Thinking Face", "Exploding Head",
        "Screaming Face", "Face with Symbols on Mouth", "Crying Face",
        "Party Popper", "Star-Struck", "Face Vomiting",
        "Pile of Poo", "Folded Hands", "Okay",
        "Dove of Peace", "Clown Face", "Yawning Face",
        "Woozy Face", "Smiling Face with Heart Eyes", "Whale",
        "Heart on Fire", "New Moon Face", "Hot Dog",
        "Hundred Points", "Rolling on the Floor Laughing", "High Voltage",
        "Banana", "Trophy", "Broken Heart",
        "Face With Raised Eyebrow", "Neutral Face", "Strawberry",
        "Champagne", "Kiss Mark", "Middle Finger",
        "Smiling Face with Horns", "Sleeping", "Loudly Crying Face",
        "Nerd Face", "Ghost", "Man Technologist",
        "Eyes", "Jack-O-Lantern", "See-No-Evil Monkey",
        "Smiling Face with Halo", "Fearful Face", "Handshake",
        "Writing Hand", "Hugging face", "Saluting face",
        "Santa Claus", "Christmas Tree", "Snowman",
        "Nail Polish", "Zany Face", "Moai",
        "Cool Button", "Heart with Arrow", "Hear-No-Evil Monkey",
        "Unicorn", "Face Blowing a Kiss", "Pill",
        "Speak-No-Evil Monkey", "Smiling Face with Sunglasses", "Alien Monster",
        "Shrugging Man", "Shrugging Person", "Shrugging Woman",
        "Rage", "Face with Tears of Joy",
    };

    /** Для каждой реакции — индексы документов по слотам, -1 если слота нет. */
    private static final int[][] SLOT_DOC = {
        {0, 1, 2, 3, 4, 5, 6}, {7, 8, 9, 10, 11, 12, 13},
        {14, 15, 16, 17, 18, 19, 20}, {21, 22, 23, 24, 25, 26, 27},
        {28, 29, 30, 31, 32, 33, 34}, {35, 36, 37, 38, 39, 40, 41},
        {42, 43, 44, 45, 46, 47, 48}, {49, 50, 51, 52, 53, 54, 55},
        {56, 57, 58, 59, 60, 61, 62}, {63, 64, 65, 66, 67, 68, 69},
        {70, 71, 72, 73, 74, 75, 76}, {77, 78, 79, 80, 81, 82, 83},
        {84, 85, 86, 87, 88, 89, 90}, {91, 92, 93, 94, 95, 96, 97},
        {98, 99, 100, 101, 102, 103, 104}, {105, 106, 107, 108, 109, 110, 111},
        {112, 113, 114, 115, 116, 117, 118}, {119, 120, 121, 122, 123, 124, 125},
        {126, 127, 128, 129, 130, 131, 132}, {133, 134, 135, 136, 137, 138, 139},
        {140, 141, 142, 143, 144, 145, 146}, {147, 148, 149, 150, 151, 152, 153},
        {154, 155, 156, 157, 158, 159, 160}, {161, 162, 163, 164, 165, 166, 167},
        {168, 169, 170, 171, 172, 173, 174}, {175, 176, 177, 178, 179, 180, 181},
        {182, 183, 184, 185, 186, 187, 188}, {189, 190, 191, 192, 193, 194, 195},
        {196, 197, 198, 199, 200, 201, 202}, {203, 204, 205, 206, 207, 208, 209},
        {210, 211, 212, 213, 214, 215, 216}, {217, 218, 219, 220, 221, 222, 223},
        {224, 225, 226, 227, 228, 229, 230}, {231, 232, 233, 234, 235, 236, 237},
        {238, 239, 240, 241, 242, 243, 244}, {245, 246, 247, 248, 249, 250, 251},
        {252, 253, 254, 255, 256, 257, 258}, {259, 260, 261, 262, 263, 264, 265},
        {266, 267, 268, 269, 270, 271, 272}, {273, 274, 275, 276, 277, 278, 279},
        {280, 281, 282, 283, 284, 285, 286}, {287, 288, 289, 290, 291, 292, 293},
        {294, 295, 296, 297, 298, 299, 300}, {301, 302, 303, 304, 305, 306, 307},
        {308, 309, 310, 311, 312, 313, 314}, {315, 316, 317, 318, 319, 320, 321},
        {322, 323, 324, 325, 326, 327, 328}, {329, 330, 331, 332, 333, 334, 335},
        {336, 337, 338, 339, 340, 341, 342}, {343, 344, 345, 346, 347, 348, 349},
        {350, 351, 352, 353, 354, 355, 356}, {357, 358, 359, 360, 361, 362, 363},
        {364, 365, 366, 367, 368, 369, 370}, {371, 372, 373, 374, 375, 376, 377},
        {378, 379, 380, 381, 382, 383, 384}, {385, 386, 387, 388, 389, 390, 391},
        {392, 393, 394, 395, 396, 397, 398}, {399, 400, 401, 402, 403, 404, 405},
        {406, 407, 408, 409, 410, 411, 412}, {413, 414, 415, 416, 417, 418, 419},
        {420, 421, 422, 423, 424, 425, 426}, {427, 428, 429, 430, 431, 432, 433},
        {434, 435, 436, 437, 438, 439, 440}, {441, 442, 443, 444, 445, 446, 447},
        {448, 449, 450, 451, 452, 453, 454}, {455, 456, 457, 458, 459, 460, 461},
        {462, 463, 464, 465, 466, 467, 468}, {469, 470, 471, 472, 473, 474, 475},
        {476, 477, 478, 479, 480, 481, 482}, {483, 484, 485, 486, 487, 488, 489},
        {490, 491, 492, 493, 494, 495, 496}, {497, 498, 499, 500, 501, 502, 503},
        {504, 505, 506, 507, 508, 509, 510}, {511, 512, 513, 514, 515, -1, -1},
    };

    private static final long[] DOC_ID = {
        4988077362902991651L, 5109641279730353573L, 5109463347825214515L, 4909424820812251759L,
        5159385139981059251L, 5098282534341444082L, 5098582486267462019L, 4988077362902991652L,
        5044361272934532415L, 5044183478468346945L, 5181593617004757506L, 5107584321108051014L,
        5098493726473323040L, 5100483636361167223L, 4988077362902991644L, 5044158348614697953L,
        5042076346038092999L, 5181852277115192162L, 5104858069142078462L, 5098223680404587072L,
        5098584835614573070L, 4988077362902991648L, 5048978706375115415L, 4976968996927570535L,
        4906965037207257780L, 5104841245755180586L, 4911313648644784662L, 4911350297600721490L,
        4988077362902991646L, 5041975474436179119L, 5044252408398480540L, 5127435509626308100L,
        5170169077011841524L, 5170493750769615324L, 5170625859668673203L, 4988077362902991642L,
        5044361315884204959L, 5044526173908894727L, 5183884724359135908L, 5170166362592510656L,
        5170378413717848677L, 5170679198867522514L, 4988077362902991649L, 5044210558237148088L,
        5042126055989576783L, 5130019946067133023L, 5048771083361059460L, 5098117732151329661L,
        5098167377678303647L, 4988077362902991658L, 5044214131649938714L, 5044590903361012688L,
        5127352539448082997L, 5170257231215591956L, 5170132621329433170L, 5172905550999978375L,
        4988077362902991645L, 5041795575436018575L, 5044032317094364254L, 5129967225343574495L,
        5158936234294248618L, 5170433075766624794L, 5170509242716652202L, 4988077362902991647L,
        5044578104358470746L, 5042029079923000379L, 5127413407724601904L, 4967494041374556629L,
        5100843060699333187L, 5100652295431913962L, 4988077362902991656L, 5044409372273280181L,
        5041797233293395674L, 5130054881331118568L, 5170149264327704981L, 5170363939678060990L,
        5170498569722921590L, 4988077362902991643L, 5044535751685964849L, 5041893904417293280L,
        5127442785300906624L, 5046551865169281494L, 5098572444633924530L, 5100672997174280859L,
        4988077362902991657L, 5107423401568371957L, 5107143249441588353L, 4909118808687378938L,
        5046509860389126442L, 5098129899793678748L, 5100597298375688653L, 4988077362902991655L,
        5042145215838684250L, 5042044344236770329L, 5129973796643537588L, 5161554034041029689L,
        5100752277975597521L, 5098487803713421690L, 4988077362902991659L, 5044591444526892224L,
        5042236187540980796L, 5129633729722974675L, 5125503964049048317L, 5098525779814252978L,
        5100750968010572125L, 4988077362902991654L, 5044193326828356887L, 5044520856739382701L,
        5129959528762180081L, 5046589136895476101L, 5098364301928825363L, 5098402595857236492L,
        4963140636753527168L, 5042203481365021639L, 5041803095923754225L, 5181833310539612725L,
        5066712811023894584L, 4960831168413958631L, 4961194020136026755L, 4925196378809828186L,
        5042184845501924569L, 5044184431951086954L, 5183799439193539297L, 5066947642655769508L,
        4987831308521570931L, 4927216649821488061L, 4925196378809828188L, 4927112733087761073L,
        4924893197068403516L, 4909440561867391667L, 5095856784057303751L, 4988006586136921228L,
        4925204556427559444L, 5316514066078899738L, 5042085872275556190L, 5044152537523946616L,
        5127512982246392449L, 4988252812317033130L, 4987994800746660332L, 4927312947283231296L,
        4925196378809828191L, 5044499003945780300L, 5044046834083824826L, 5127886957933756781L,
        4988134357119009237L, 4987866406994313953L, 4927473119498601100L, 4925196378809828190L,
        5041913592547378375L, 5042079326745396060L, 5129823378298897000L, 4927250902185673331L,
        4936175955895910982L, 4924745630582047477L, 4925196378809828189L, 5041878648693457670L,
        5042106745816616550L, 5130137765609996918L, 5066779159678682060L, 4988072290546614932L,
        4927006260848492996L, 4925196378809828193L, 5109799587929916637L, 5109846098130764622L,
        4906680246515794715L, 5075655057488217321L, 4987843128271569406L, 4924743259760100089L,
        4963140636753527164L, 5109652695753426222L, 5109572083512247479L, 4907155381567881876L,
        5066576334143095943L, 4988234004655243825L, 4963507963831517686L, 4963140636753527165L,
        5109837946282837046L, 5109438239446403094L, 4907196909606666791L, 4961155507164283482L,
        4988215656554955363L, 4963478337147110091L, 4963140636753527166L, 5107392851465995335L,
        5107585158626673731L, 4909299098529563381L, 4961017943656759969L, 4963333686943547962L,
        4961056933369872906L, 4963140636753527167L, 5107576517152474051L, 5109666143296029687L,
        4909335412478051032L, 4962976753686414048L, 4963295225511412281L, 4960918403494707927L,
        5005920125929390907L, 5041937747443451090L, 5041975006284744178L, 5129961152259818018L,
        5066993302453093673L, 5003900146975507270L, 5003790724093706757L, 5125212563402916292L,
        5109447069899162616L, 5109499090543051948L, 4909292076258034219L, 5123236135417415011L,
        5125292235046257499L, 5125310346923344381L, 5125212563402916293L, 5122923607827153459L,
        5125481471305318946L, 4909049621059207722L, 5123233223429587601L, 5125314422847308346L,
        5125325147380646567L, 5125212563402916294L, 5125221509819794092L, 5122948016126296720L,
        4908957081693848377L, 5123046001510188023L, 5123368102582551142L, 5125386251880366497L,
        5125212563402916295L, 5107362679320740973L, 5109379488588759939L, 4907190802163172128L,
        5123265598893064880L, 5125474105436406440L, 5123152606893441763L, 5125212563402916296L,
        5125614834334827338L, 5123033159557972704L, 5130064437633352245L, 5122846324185629167L,
        5125164545668547147L, 5123142075633631819L, 5125212563402916297L, 5125407679472206248L,
        5125381819474117077L, 5129653108615414540L, 5066978240002786236L, 5125234489210962558L,
        5122932695977952114L, 4963178024443839281L, 4960988969807381138L, 4960879053004342956L,
        4909386771696976395L, 4961016393173566021L, 4961015340906578725L, 4961272003857220077L,
        4963178024443839280L, 5107517306733331742L, 5107226447253079145L, 4909209600001049317L,
        4961122255527478034L, 4960973666838906012L, 4963361333648032551L, 4963178024443839279L,
        5107169564706211333L, 5107445335966352621L, 4909064872488075979L, 4963232093787128618L,
        4962990510466663097L, 4963318766227161666L, 4963178024443839278L, 4963167347155141295L,
        4961083527807370699L, 5181659450263470849L, 4961092903720977544L, 4963452137846604463L,
        4963496217095963299L, 4963178024443839277L, 4963438935117136471L, 4960923334117163570L,
        5127572991529452285L, 4960944078809203417L, 4963231990707913347L, 4963083393429406100L,
        5107577036843516381L, 5107636049694163693L, 5107089192983200422L, 5130139268848550318L,
        5109477370893435421L, 5107087835773534966L, 5109793918573085400L, 5107577036843516382L,
        5109838895470609153L, 5107376556360074051L, 5127504688664543876L, 5069111056337470630L,
        5107216074907058832L, 5107454381167477319L, 5107577036843516383L, 5107535418610418757L,
        5109744054002779213L, 5130116312248353364L, 5107633812016202563L, 5107383389653041955L,
        5107434912080724668L, 5107577036843516384L, 5109856556376130410L, 5107110813848569286L,
        5129679273556181537L, 5107144215809229474L, 5107153256715387699L, 5109624860070380116L,
        5107577036843516385L, 5107119055890809464L, 5109353242043614255L, 4907007900980871941L,
        5107153978269893301L, 5107451529309192935L, 5107327598027867392L, 5107577036843516386L,
        5107225996281512503L, 5107614901275198375L, 4906904890485244443L, 5107400329004058326L,
        5107284575340462617L, 5107507707481424675L, 5107577036843516387L, 5107159832310317964L,
        5107325347465003719L, 5129972598347661778L, 5107491876231971605L, 5107171321347834511L,
        5107319837021963311L, 4913431256500274107L, 4913704034168212193L, 4915873877415952947L,
        4909357540149560027L, 4913625371842183765L, 4913605567747982041L, 4913898510287372933L,
        4913431256500274108L, 4913513780001899157L, 4916174069860139658L, 5127690222661796498L,
        4913435779100836551L, 4915854618782598099L, 4913530143827296956L, 4913431256500274109L,
        4913455969742095097L, 4913600783154414275L, 5129715527375127060L, 4913510691920413388L,
        4915928217342183276L, 4913791148989874692L, 4913431256500274110L, 4913649569687929387L,
        4913500018926682928L, 4963195715414131468L, 4916165059018752691L, 4916119266077442781L,
        4913503974591562391L, 4913431256500274111L, 4913671083179115190L, 4915957586328552111L,
        5181902103030793161L, 4913460264709391187L, 4913667415277044538L, 4913987566434255617L,
        4913431256500274112L, 4915830455296590634L, 4913737564977889929L, 5129601143806100328L,
        4913445953878360935L, 4913543075973825389L, 4913687983875424993L, 4913431256500274113L,
        4913597153907049318L, 4913771039952994912L, 5129867650821784171L, 4913930782671635058L,
        4913484183382263356L, 4913902444477416232L, 4990112567515939494L, 4990464260912972660L,
        4992509438439982558L, 4908949136004350823L, 4990285736302346915L, 4990439225548603958L,
        4990231598239581081L, 5006283127975314083L, 4990315556260283313L, 4990134643647840947L,
        4906829303355802062L, 5066610182780355771L, 5006037524565459906L, 4992756983175053968L,
        5006283127975314084L, 4990494463122997793L, 4990255581336961925L, 4909109437068739325L,
        4989997728680380153L, 4990315719469040325L, 4992667424516997852L, 5005914529587004234L,
        5003741340559737501L, 5006253084679078763L, 5024065533826302746L, 5003596415478268711L,
        5006133985235960489L, 5006016801348256429L, 5005914529587004236L, 5006179288550998793L,
        5003697802476258115L, 5127684445930783362L, 5006239241999483552L, 5005896559443838157L,
        5006171420170912477L, 5005914529587004235L, 5006010406141952606L, 5006112982845883166L,
        4909234077019669180L, 5006084210859967333L, 5005834991087649841L, 5005847815859995281L,
        5005914529587004237L, 5006032211690914548L, 5003499460886528696L, 5089170894366966396L,
        5006150512270115734L, 5004059382888006547L, 5006100093649027909L, 5089164786923471992L,
        5089535743248827210L, 5089528879891087924L, 4909273122567357168L, 5089139004234793667L,
        5089352176346596020L, 5089404682321789684L, 5089164786923471993L, 5089068781519504053L,
        5089266972785378058L, 4909165731205088154L, 5089358816366035753L, 5089627342016349044L,
        5089526354450317861L, 5089164786923471994L, 5089432277486666442L, 5089611231594021682L,
        4913514935348102286L, 5089416218603946547L, 5089090084557292352L, 5089504772239655597L,
        5089164786923471995L, 5089473504877740664L, 5089446055741751875L, 5129608062998413793L,
        5159305906424382943L, 5089449852492841769L, 5089538638056784481L, 5089164786923471996L,
        5089211829700264571L, 5089318529572799212L, 4909073260559204904L, 5089598484131087029L,
        5089557815085761088L, 5089434201632015241L, 5089164786923471999L, 5089175172154393153L,
        5089419190721315471L, 4909334690923545222L, 5089447945527362129L, 5089315338412098237L,
        5089558875942683463L, 4924779393319961212L, 4924932727947395844L, 4927042566207046734L,
        5127354905975063095L, 4925230459375322151L, 4925082360313021269L, 4925077283661677336L,
        4924779393319961213L, 4924956247188308878L, 4924700460410995408L, 5127441853293003483L,
        4927184970142712981L, 4925085405444833931L, 4924857621854290598L, 4924779393319961214L,
        4925072937154773857L, 4924967916614451872L, 4909483932447146746L, 5069188799540495289L,
        4929694764346901391L, 4927383105574011766L, 4924779393319961215L, 4925225228105155836L,
        4925109474441560825L, 4925168092155216450L, 5068838802655544199L, 4929531276416778831L,
        4927112548404167279L, 4924779393319961216L, 4925081350995706871L, 4924953876366361503L,
        4907133631853495259L, 5066849184825475891L, 4927073133489292373L, 4925196056687281086L,
        4924779393319961217L, 4924793150100210529L, 4924842520749277994L, 5129791445217051348L,
        4925068178331010095L, 4925114465193558902L, 4924867401494823611L, 5051344365771817404L,
        5051161069452526074L, 4978848667889828269L, 5130017893072765649L, 5127791914602463817L,
    };

    private static final int[] DOC_DC = {
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    };

    private static final String[] DOC_MIME = {
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "image/webp",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "image/webp", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
        "application/x-tgsticker", "image/webp", "application/x-tgsticker",
        "application/x-tgsticker", "application/x-tgsticker", "application/x-tgsticker",
    };

    private static final int[] DOC_SIZE = {
        1564, 58393, 10190, 56477, 64806, 10758, 1861, 1462, 10598, 12870,
        25835, 61628, 56405, 3058, 1396, 7943, 9284, 7522, 60486, 57166,
        2913, 1862, 10769, 14298, 26099, 60097, 45770, 8070, 2034, 9735,
        15610, 10658, 57563, 38128, 5272, 1860, 20291, 22835, 25844, 65363,
        41761, 8488, 1918, 9285, 11330, 17282, 44080, 17415, 3800, 1720,
        11001, 18466, 17004, 27025, 14337, 4298, 2510, 21910, 33180, 47679,
        34923, 14764, 11861, 2012, 6021, 6207, 12083, 28923, 12060, 5366,
        2072, 8411, 13672, 15698, 63871, 27547, 7353, 1904, 5455, 6172,
        6769, 40063, 10634, 2609, 3310, 8411, 9885, 14103, 13089, 7536,
        11726, 1996, 10834, 9979, 18834, 51097, 8420, 4319, 1862, 25571,
        29644, 30490, 63523, 41225, 5192, 1792, 16595, 11333, 13670, 62958,
        32726, 5973, 2640, 27631, 33719, 34559, 61438, 23998, 5446, 1492,
        15529, 22074, 20382, 50427, 33923, 3637, 3268, 30193, 40408, 65469,
        31913, 23624, 17276, 5302, 16057, 22199, 47913, 46711, 44000, 5798,
        2760, 10220, 18870, 12842, 53700, 62227, 5606, 3712, 11720, 11872,
        18541, 57539, 54651, 5859, 1966, 5333, 7145, 5032, 63247, 26037,
        4280, 3596, 16306, 42961, 60319, 60977, 42643, 16665, 3958, 16200,
        32684, 35943, 52149, 22548, 18422, 3832, 12281, 20158, 22957, 55654,
        45644, 6214, 3676, 16198, 18267, 32460, 64260, 48008, 5935, 1704,
        29090, 51352, 45925, 65263, 56876, 19821, 3814, 13077, 12972, 15738,
        63177, 59479, 5252, 1786, 13699, 6015, 33261, 58245, 54523, 12043,
        1500, 23469, 29488, 65513, 57582, 45139, 5815, 2428, 31517, 38554,
        60319, 58798, 35021, 7719, 2062, 5346, 7917, 5978, 65099, 58381,
        2305, 1708, 8119, 10943, 10751, 64973, 60787, 2897, 2042, 3782,
        4794, 2786, 56439, 42247, 1443, 4366, 56274, 58427, 64753, 60370,
        39533, 15649, 2992, 23055, 44318, 42245, 52044, 38045, 8693, 1816,
        12058, 12350, 23467, 42543, 39190, 13332, 1968, 9821, 18973, 17710,
        60291, 37038, 5151, 1462, 9727, 11672, 12171, 61827, 58876, 3011,
        1444, 9356, 10442, 15813, 64980, 49468, 4275, 2846, 11801, 20663,
        51420, 62792, 64178, 4666, 1718, 25562, 33154, 45234, 54564, 47148,
        5134, 2388, 17325, 17220, 23799, 65462, 43268, 7754, 4352, 12024,
        21153, 21265, 31278, 23997, 11018, 3358, 5121, 7752, 6587, 43202,
        44540, 2124, 3192, 17915, 37572, 65244, 43862, 58078, 11189, 6068,
        11232, 15476, 23871, 65069, 65026, 6206, 3016, 10308, 19818, 24250,
        55136, 32958, 12851, 1456, 7448, 17106, 22854, 65527, 46238, 3166,
        1296, 38316, 47677, 48474, 65179, 39311, 9836, 2442, 16927, 39347,
        49974, 52056, 25753, 7485, 3198, 28915, 36910, 37549, 62058, 43602,
        11250, 758, 20730, 35548, 35675, 65343, 45446, 10385, 4038, 18598,
        24804, 47627, 64428, 36524, 9720, 3834, 22038, 26150, 28793, 60392,
        43285, 5253, 4618, 17985, 20404, 19328, 64541, 49460, 6083, 3396,
        3481, 8047, 10301, 63266, 32673, 5623, 3768, 10118, 18896, 21600,
        61602, 45826, 6392, 1682, 21099, 34805, 34500, 58233, 56099, 3688,
        1366, 8089, 7444, 24428, 42698, 26969, 4280, 1964, 9311, 14688,
        13316, 61716, 36963, 4220, 5752, 15163, 17639, 17096, 65388, 43670,
        8219, 2120, 29007, 43186, 58428, 51486, 37210, 7906, 8926, 7101,
        9841, 9541, 48765, 30832, 3459, 1740, 1921, 6581, 7381, 59385,
        23364, 1826, 3986, 13726, 14540, 14534, 62579, 64079, 6596, 5068,
        13345, 13774, 14395, 64788, 24037, 3781, 926, 3905, 3479, 4791,
        53961, 40488, 2532, 4136, 15132, 17958, 19825, 65481, 40366, 8689,
        3762, 17872, 20925, 24304, 65481, 40366, 10569, 4306, 18626, 21341,
        24055, 65481, 40366, 10174, 7396, 4279, 8430, 8308, 64909, 63200,
        3627, 3220, 7709, 7918, 18565, 62191,
    };

    private static final int[] DOC_WIDTH = {
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 73, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 300, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        299, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
    };

    private static final int[] DOC_HEIGHT = {
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
        512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512,
        512, 512, 512, 512, 300, 512, 512, 512, 512, 512, 512, 72,
        512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512,
        512, 72, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512,
        512, 512, 512, 72, 512, 512, 512, 512, 512, 512, 72, 512,
        512, 512, 512, 512, 512, 72, 512, 512, 512, 512, 512, 512,
        299, 512, 512, 512, 512, 512, 512, 72, 512, 512, 512, 512,
    };

    private static final String[] DOC_FILE = {
        "tg/4988077362902991651.webp", "tg/5109641279730353573.tgs", "tg/5109463347825214515.tgs",
        "tg/4909424820812251759.tgs", "tg/5159385139981059251.tgs", "tg/5098282534341444082.tgs",
        "tg/5098582486267462019.tgs", "tg/4988077362902991652.webp", "tg/5044361272934532415.tgs",
        "tg/5044183478468346945.tgs", "tg/5181593617004757506.tgs", "tg/5107584321108051014.tgs",
        "tg/5098493726473323040.tgs", "tg/5100483636361167223.tgs", "tg/4988077362902991644.webp",
        "tg/5044158348614697953.tgs", "tg/5042076346038092999.tgs", "tg/5181852277115192162.tgs",
        "tg/5104858069142078462.tgs", "tg/5098223680404587072.tgs", "tg/5098584835614573070.tgs",
        "tg/4988077362902991648.webp", "tg/5048978706375115415.tgs", "tg/4976968996927570535.tgs",
        "tg/4906965037207257780.tgs", "tg/5104841245755180586.tgs", "tg/4911313648644784662.tgs",
        "tg/4911350297600721490.tgs", "tg/4988077362902991646.webp", "tg/5041975474436179119.tgs",
        "tg/5044252408398480540.tgs", "tg/5127435509626308100.tgs", "tg/5170169077011841524.tgs",
        "tg/5170493750769615324.tgs", "tg/5170625859668673203.tgs", "tg/4988077362902991642.webp",
        "tg/5044361315884204959.tgs", "tg/5044526173908894727.tgs", "tg/5183884724359135908.tgs",
        "tg/5170166362592510656.tgs", "tg/5170378413717848677.tgs", "tg/5170679198867522514.tgs",
        "tg/4988077362902991649.webp", "tg/5044210558237148088.tgs", "tg/5042126055989576783.tgs",
        "tg/5130019946067133023.tgs", "tg/5048771083361059460.tgs", "tg/5098117732151329661.tgs",
        "tg/5098167377678303647.tgs", "tg/4988077362902991658.webp", "tg/5044214131649938714.tgs",
        "tg/5044590903361012688.tgs", "tg/5127352539448082997.tgs", "tg/5170257231215591956.tgs",
        "tg/5170132621329433170.tgs", "tg/5172905550999978375.tgs", "tg/4988077362902991645.webp",
        "tg/5041795575436018575.tgs", "tg/5044032317094364254.tgs", "tg/5129967225343574495.tgs",
        "tg/5158936234294248618.tgs", "tg/5170433075766624794.tgs", "tg/5170509242716652202.tgs",
        "tg/4988077362902991647.webp", "tg/5044578104358470746.tgs", "tg/5042029079923000379.tgs",
        "tg/5127413407724601904.tgs", "tg/4967494041374556629.tgs", "tg/5100843060699333187.tgs",
        "tg/5100652295431913962.tgs", "tg/4988077362902991656.webp", "tg/5044409372273280181.tgs",
        "tg/5041797233293395674.tgs", "tg/5130054881331118568.tgs", "tg/5170149264327704981.tgs",
        "tg/5170363939678060990.tgs", "tg/5170498569722921590.tgs", "tg/4988077362902991643.webp",
        "tg/5044535751685964849.tgs", "tg/5041893904417293280.tgs", "tg/5127442785300906624.tgs",
        "tg/5046551865169281494.tgs", "tg/5098572444633924530.tgs", "tg/5100672997174280859.tgs",
        "tg/4988077362902991657.webp", "tg/5107423401568371957.tgs", "tg/5107143249441588353.tgs",
        "tg/4909118808687378938.tgs", "tg/5046509860389126442.tgs", "tg/5098129899793678748.tgs",
        "tg/5100597298375688653.tgs", "tg/4988077362902991655.webp", "tg/5042145215838684250.tgs",
        "tg/5042044344236770329.tgs", "tg/5129973796643537588.tgs", "tg/5161554034041029689.tgs",
        "tg/5100752277975597521.tgs", "tg/5098487803713421690.tgs", "tg/4988077362902991659.webp",
        "tg/5044591444526892224.tgs", "tg/5042236187540980796.tgs", "tg/5129633729722974675.tgs",
        "tg/5125503964049048317.tgs", "tg/5098525779814252978.tgs", "tg/5100750968010572125.tgs",
        "tg/4988077362902991654.webp", "tg/5044193326828356887.tgs", "tg/5044520856739382701.tgs",
        "tg/5129959528762180081.tgs", "tg/5046589136895476101.tgs", "tg/5098364301928825363.tgs",
        "tg/5098402595857236492.tgs", "tg/4963140636753527168.webp", "tg/5042203481365021639.tgs",
        "tg/5041803095923754225.tgs", "tg/5181833310539612725.tgs", "tg/5066712811023894584.tgs",
        "tg/4960831168413958631.tgs", "tg/4961194020136026755.tgs", "tg/4925196378809828186.webp",
        "tg/5042184845501924569.tgs", "tg/5044184431951086954.tgs", "tg/5183799439193539297.tgs",
        "tg/5066947642655769508.tgs", "tg/4987831308521570931.tgs", "tg/4927216649821488061.tgs",
        "tg/4925196378809828188.webp", "tg/4927112733087761073.tgs", "tg/4924893197068403516.tgs",
        "tg/4909440561867391667.tgs", "tg/5095856784057303751.tgs", "tg/4988006586136921228.tgs",
        "tg/4925204556427559444.tgs", "tg/5316514066078899738.webp", "tg/5042085872275556190.tgs",
        "tg/5044152537523946616.tgs", "tg/5127512982246392449.tgs", "tg/4988252812317033130.tgs",
        "tg/4987994800746660332.tgs", "tg/4927312947283231296.tgs", "tg/4925196378809828191.webp",
        "tg/5044499003945780300.tgs", "tg/5044046834083824826.tgs", "tg/5127886957933756781.tgs",
        "tg/4988134357119009237.tgs", "tg/4987866406994313953.tgs", "tg/4927473119498601100.tgs",
        "tg/4925196378809828190.webp", "tg/5041913592547378375.tgs", "tg/5042079326745396060.tgs",
        "tg/5129823378298897000.tgs", "tg/4927250902185673331.tgs", "tg/4936175955895910982.tgs",
        "tg/4924745630582047477.tgs", "tg/4925196378809828189.webp", "tg/5041878648693457670.tgs",
        "tg/5042106745816616550.tgs", "tg/5130137765609996918.tgs", "tg/5066779159678682060.tgs",
        "tg/4988072290546614932.tgs", "tg/4927006260848492996.tgs", "tg/4925196378809828193.webp",
        "tg/5109799587929916637.tgs", "tg/5109846098130764622.tgs", "tg/4906680246515794715.tgs",
        "tg/5075655057488217321.tgs", "tg/4987843128271569406.tgs", "tg/4924743259760100089.tgs",
        "tg/4963140636753527164.webp", "tg/5109652695753426222.tgs", "tg/5109572083512247479.tgs",
        "tg/4907155381567881876.tgs", "tg/5066576334143095943.tgs", "tg/4988234004655243825.tgs",
        "tg/4963507963831517686.tgs", "tg/4963140636753527165.webp", "tg/5109837946282837046.tgs",
        "tg/5109438239446403094.tgs", "tg/4907196909606666791.tgs", "tg/4961155507164283482.tgs",
        "tg/4988215656554955363.tgs", "tg/4963478337147110091.tgs", "tg/4963140636753527166.webp",
        "tg/5107392851465995335.tgs", "tg/5107585158626673731.tgs", "tg/4909299098529563381.tgs",
        "tg/4961017943656759969.tgs", "tg/4963333686943547962.tgs", "tg/4961056933369872906.tgs",
        "tg/4963140636753527167.webp", "tg/5107576517152474051.tgs", "tg/5109666143296029687.tgs",
        "tg/4909335412478051032.tgs", "tg/4962976753686414048.tgs", "tg/4963295225511412281.tgs",
        "tg/4960918403494707927.tgs", "tg/5005920125929390907.webp", "tg/5041937747443451090.tgs",
        "tg/5041975006284744178.tgs", "tg/5129961152259818018.tgs", "tg/5066993302453093673.tgs",
        "tg/5003900146975507270.tgs", "tg/5003790724093706757.tgs", "tg/5125212563402916292.webp",
        "tg/5109447069899162616.tgs", "tg/5109499090543051948.tgs", "tg/4909292076258034219.tgs",
        "tg/5123236135417415011.tgs", "tg/5125292235046257499.tgs", "tg/5125310346923344381.tgs",
        "tg/5125212563402916293.webp", "tg/5122923607827153459.tgs", "tg/5125481471305318946.tgs",
        "tg/4909049621059207722.tgs", "tg/5123233223429587601.tgs", "tg/5125314422847308346.tgs",
        "tg/5125325147380646567.tgs", "tg/5125212563402916294.webp", "tg/5125221509819794092.tgs",
        "tg/5122948016126296720.tgs", "tg/4908957081693848377.tgs", "tg/5123046001510188023.tgs",
        "tg/5123368102582551142.tgs", "tg/5125386251880366497.tgs", "tg/5125212563402916295.webp",
        "tg/5107362679320740973.tgs", "tg/5109379488588759939.tgs", "tg/4907190802163172128.tgs",
        "tg/5123265598893064880.tgs", "tg/5125474105436406440.tgs", "tg/5123152606893441763.tgs",
        "tg/5125212563402916296.webp", "tg/5125614834334827338.tgs", "tg/5123033159557972704.tgs",
        "tg/5130064437633352245.tgs", "tg/5122846324185629167.tgs", "tg/5125164545668547147.tgs",
        "tg/5123142075633631819.tgs", "tg/5125212563402916297.webp", "tg/5125407679472206248.tgs",
        "tg/5125381819474117077.tgs", "tg/5129653108615414540.tgs", "tg/5066978240002786236.tgs",
        "tg/5125234489210962558.tgs", "tg/5122932695977952114.tgs", "tg/4963178024443839281.webp",
        "tg/4960988969807381138.tgs", "tg/4960879053004342956.tgs", "tg/4909386771696976395.tgs",
        "tg/4961016393173566021.tgs", "tg/4961015340906578725.tgs", "tg/4961272003857220077.tgs",
        "tg/4963178024443839280.webp", "tg/5107517306733331742.tgs", "tg/5107226447253079145.tgs",
        "tg/4909209600001049317.tgs", "tg/4961122255527478034.tgs", "tg/4960973666838906012.tgs",
        "tg/4963361333648032551.tgs", "tg/4963178024443839279.webp", "tg/5107169564706211333.tgs",
        "tg/5107445335966352621.tgs", "tg/4909064872488075979.tgs", "tg/4963232093787128618.tgs",
        "tg/4962990510466663097.tgs", "tg/4963318766227161666.tgs", "tg/4963178024443839278.webp",
        "tg/4963167347155141295.tgs", "tg/4961083527807370699.tgs", "tg/5181659450263470849.tgs",
        "tg/4961092903720977544.tgs", "tg/4963452137846604463.tgs", "tg/4963496217095963299.tgs",
        "tg/4963178024443839277.webp", "tg/4963438935117136471.tgs", "tg/4960923334117163570.tgs",
        "tg/5127572991529452285.tgs", "tg/4960944078809203417.tgs", "tg/4963231990707913347.tgs",
        "tg/4963083393429406100.tgs", "tg/5107577036843516381.webp", "tg/5107636049694163693.tgs",
        "tg/5107089192983200422.tgs", "tg/5130139268848550318.tgs", "tg/5109477370893435421.tgs",
        "tg/5107087835773534966.tgs", "tg/5109793918573085400.tgs", "tg/5107577036843516382.webp",
        "tg/5109838895470609153.tgs", "tg/5107376556360074051.tgs", "tg/5127504688664543876.tgs",
        "tg/5069111056337470630.tgs", "tg/5107216074907058832.tgs", "tg/5107454381167477319.tgs",
        "tg/5107577036843516383.webp", "tg/5107535418610418757.tgs", "tg/5109744054002779213.tgs",
        "tg/5130116312248353364.tgs", "tg/5107633812016202563.tgs", "tg/5107383389653041955.tgs",
        "tg/5107434912080724668.tgs", "tg/5107577036843516384.webp", "tg/5109856556376130410.tgs",
        "tg/5107110813848569286.tgs", "tg/5129679273556181537.tgs", "tg/5107144215809229474.tgs",
        "tg/5107153256715387699.tgs", "tg/5109624860070380116.tgs", "tg/5107577036843516385.webp",
        "tg/5107119055890809464.tgs", "tg/5109353242043614255.tgs", "tg/4907007900980871941.tgs",
        "tg/5107153978269893301.tgs", "tg/5107451529309192935.tgs", "tg/5107327598027867392.tgs",
        "tg/5107577036843516386.webp", "tg/5107225996281512503.tgs", "tg/5107614901275198375.tgs",
        "tg/4906904890485244443.tgs", "tg/5107400329004058326.tgs", "tg/5107284575340462617.tgs",
        "tg/5107507707481424675.tgs", "tg/5107577036843516387.webp", "tg/5107159832310317964.tgs",
        "tg/5107325347465003719.tgs", "tg/5129972598347661778.tgs", "tg/5107491876231971605.tgs",
        "tg/5107171321347834511.tgs", "tg/5107319837021963311.tgs", "tg/4913431256500274107.webp",
        "tg/4913704034168212193.tgs", "tg/4915873877415952947.tgs", "tg/4909357540149560027.tgs",
        "tg/4913625371842183765.tgs", "tg/4913605567747982041.tgs", "tg/4913898510287372933.tgs",
        "tg/4913431256500274108.webp", "tg/4913513780001899157.tgs", "tg/4916174069860139658.tgs",
        "tg/5127690222661796498.tgs", "tg/4913435779100836551.tgs", "tg/4915854618782598099.tgs",
        "tg/4913530143827296956.tgs", "tg/4913431256500274109.webp", "tg/4913455969742095097.tgs",
        "tg/4913600783154414275.tgs", "tg/5129715527375127060.tgs", "tg/4913510691920413388.tgs",
        "tg/4915928217342183276.tgs", "tg/4913791148989874692.tgs", "tg/4913431256500274110.webp",
        "tg/4913649569687929387.tgs", "tg/4913500018926682928.tgs", "tg/4963195715414131468.tgs",
        "tg/4916165059018752691.tgs", "tg/4916119266077442781.tgs", "tg/4913503974591562391.tgs",
        "tg/4913431256500274111.webp", "tg/4913671083179115190.tgs", "tg/4915957586328552111.tgs",
        "tg/5181902103030793161.tgs", "tg/4913460264709391187.tgs", "tg/4913667415277044538.tgs",
        "tg/4913987566434255617.tgs", "tg/4913431256500274112.webp", "tg/4915830455296590634.tgs",
        "tg/4913737564977889929.tgs", "tg/5129601143806100328.tgs", "tg/4913445953878360935.tgs",
        "tg/4913543075973825389.tgs", "tg/4913687983875424993.tgs", "tg/4913431256500274113.webp",
        "tg/4913597153907049318.tgs", "tg/4913771039952994912.tgs", "tg/5129867650821784171.tgs",
        "tg/4913930782671635058.tgs", "tg/4913484183382263356.tgs", "tg/4913902444477416232.tgs",
        "tg/4990112567515939494.webp", "tg/4990464260912972660.tgs", "tg/4992509438439982558.tgs",
        "tg/4908949136004350823.tgs", "tg/4990285736302346915.tgs", "tg/4990439225548603958.tgs",
        "tg/4990231598239581081.tgs", "tg/5006283127975314083.webp", "tg/4990315556260283313.tgs",
        "tg/4990134643647840947.tgs", "tg/4906829303355802062.tgs", "tg/5066610182780355771.tgs",
        "tg/5006037524565459906.tgs", "tg/4992756983175053968.tgs", "tg/5006283127975314084.webp",
        "tg/4990494463122997793.tgs", "tg/4990255581336961925.tgs", "tg/4909109437068739325.tgs",
        "tg/4989997728680380153.tgs", "tg/4990315719469040325.tgs", "tg/4992667424516997852.tgs",
        "tg/5005914529587004234.webp", "tg/5003741340559737501.tgs", "tg/5006253084679078763.tgs",
        "tg/5024065533826302746.tgs", "tg/5003596415478268711.tgs", "tg/5006133985235960489.tgs",
        "tg/5006016801348256429.tgs", "tg/5005914529587004236.webp", "tg/5006179288550998793.tgs",
        "tg/5003697802476258115.tgs", "tg/5127684445930783362.tgs", "tg/5006239241999483552.tgs",
        "tg/5005896559443838157.tgs", "tg/5006171420170912477.tgs", "tg/5005914529587004235.webp",
        "tg/5006010406141952606.tgs", "tg/5006112982845883166.tgs", "tg/4909234077019669180.tgs",
        "tg/5006084210859967333.tgs", "tg/5005834991087649841.tgs", "tg/5005847815859995281.tgs",
        "tg/5005914529587004237.webp", "tg/5006032211690914548.tgs", "tg/5003499460886528696.tgs",
        "tg/5089170894366966396.tgs", "tg/5006150512270115734.tgs", "tg/5004059382888006547.tgs",
        "tg/5006100093649027909.tgs", "tg/5089164786923471992.webp", "tg/5089535743248827210.tgs",
        "tg/5089528879891087924.tgs", "tg/4909273122567357168.tgs", "tg/5089139004234793667.tgs",
        "tg/5089352176346596020.tgs", "tg/5089404682321789684.tgs", "tg/5089164786923471993.webp",
        "tg/5089068781519504053.tgs", "tg/5089266972785378058.tgs", "tg/4909165731205088154.tgs",
        "tg/5089358816366035753.tgs", "tg/5089627342016349044.tgs", "tg/5089526354450317861.tgs",
        "tg/5089164786923471994.webp", "tg/5089432277486666442.tgs", "tg/5089611231594021682.tgs",
        "tg/4913514935348102286.tgs", "tg/5089416218603946547.tgs", "tg/5089090084557292352.tgs",
        "tg/5089504772239655597.tgs", "tg/5089164786923471995.webp", "tg/5089473504877740664.tgs",
        "tg/5089446055741751875.tgs", "tg/5129608062998413793.tgs", "tg/5159305906424382943.tgs",
        "tg/5089449852492841769.tgs", "tg/5089538638056784481.tgs", "tg/5089164786923471996.webp",
        "tg/5089211829700264571.tgs", "tg/5089318529572799212.tgs", "tg/4909073260559204904.tgs",
        "tg/5089598484131087029.tgs", "tg/5089557815085761088.tgs", "tg/5089434201632015241.tgs",
        "tg/5089164786923471999.webp", "tg/5089175172154393153.tgs", "tg/5089419190721315471.tgs",
        "tg/4909334690923545222.tgs", "tg/5089447945527362129.tgs", "tg/5089315338412098237.tgs",
        "tg/5089558875942683463.tgs", "tg/4924779393319961212.webp", "tg/4924932727947395844.tgs",
        "tg/4927042566207046734.tgs", "tg/5127354905975063095.tgs", "tg/4925230459375322151.tgs",
        "tg/4925082360313021269.tgs", "tg/4925077283661677336.tgs", "tg/4924779393319961213.webp",
        "tg/4924956247188308878.tgs", "tg/4924700460410995408.tgs", "tg/5127441853293003483.tgs",
        "tg/4927184970142712981.tgs", "tg/4925085405444833931.tgs", "tg/4924857621854290598.tgs",
        "tg/4924779393319961214.webp", "tg/4925072937154773857.tgs", "tg/4924967916614451872.tgs",
        "tg/4909483932447146746.tgs", "tg/5069188799540495289.tgs", "tg/4929694764346901391.tgs",
        "tg/4927383105574011766.tgs", "tg/4924779393319961215.webp", "tg/4925225228105155836.tgs",
        "tg/4925109474441560825.tgs", "tg/4925168092155216450.tgs", "tg/5068838802655544199.tgs",
        "tg/4929531276416778831.tgs", "tg/4927112548404167279.tgs", "tg/4924779393319961216.webp",
        "tg/4925081350995706871.tgs", "tg/4924953876366361503.tgs", "tg/4907133631853495259.tgs",
        "tg/5066849184825475891.tgs", "tg/4927073133489292373.tgs", "tg/4925196056687281086.tgs",
        "tg/4924779393319961217.webp", "tg/4924793150100210529.tgs", "tg/4924842520749277994.tgs",
        "tg/5129791445217051348.tgs", "tg/4925068178331010095.tgs", "tg/4925114465193558902.tgs",
        "tg/4924867401494823611.tgs", "tg/5051344365771817404.webp", "tg/5051161069452526074.tgs",
        "tg/4978848667889828269.tgs", "tg/5130017893072765649.tgs", "tg/5127791914602463817.tgs",
    };

    private static final HashMap<String, Integer> INDEX = new HashMap<>();
    private static final HashMap<Long, String> FILES = new HashMap<>();
    private static final HashMap<Long, TLRPC.Document> CACHE = new HashMap<>();

    static {
        for (int i = 0; i < EMOJI.length; i++) {
            INDEX.put(WaysGramReactions.normalize(EMOJI[i]), i);
        }
        for (int i = 0; i < DOC_ID.length; i++) {
            FILES.put(DOC_ID[i], DOC_FILE[i]);
        }
    }

    private WaysGramReactionAssets() {
    }

    static int count() {
        return EMOJI.length;
    }

    /** Индекс реакции по эмодзи (U+FE0F и оттенки кожи не важны), -1 если нет. */
    static int indexOf(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return -1;
        }
        Integer found = INDEX.get(WaysGramReactions.normalize(emoji));
        return found == null ? -1 : found;
    }

    static int slotIndex(String slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i].equals(slot)) {
                return i;
            }
        }
        return -1;
    }

    /** Идентификатор документа в слоте или -1. По нему считается хеш каталога. */
    static long rawId(int reaction, String slot) {
        if (reaction < 0 || reaction >= SLOT_DOC.length) {
            return -1;
        }
        int slotIndex = slotIndex(slot);
        if (slotIndex < 0) {
            return -1;
        }
        int index = SLOT_DOC[reaction][slotIndex];
        return index < 0 ? -1 : DOC_ID[index];
    }

    /** Документ реакции по слоту или {@code null}, если Telegram его не отдал. */
    static synchronized TLRPC.Document document(int reaction, String slot) {
        if (reaction < 0 || reaction >= SLOT_DOC.length) {
            return null;
        }
        int slotIndex = slotIndex(slot);
        if (slotIndex < 0) {
            return null;
        }
        int document = SLOT_DOC[reaction][slotIndex];
        return document < 0 ? null : document(document);
    }

    /** Документ по своему индексу. */
    static synchronized TLRPC.Document document(int index) {
        if (index < 0 || index >= DOC_ID.length) {
            return null;
        }
        TLRPC.Document cached = CACHE.get(DOC_ID[index]);
        if (cached != null) {
            return cached;
        }
        try {
            TLRPC.TL_document doc = new TLRPC.TL_document();
            doc.id = doc.access_hash = DOC_ID[index];
            doc.dc_id = DOC_DC[index];
            doc.file_reference = new byte[0];
            doc.date = (int) (System.currentTimeMillis() / 1000);
            doc.mime_type = DOC_MIME[index];
            doc.size = DOC_SIZE[index];
            TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize();
            dimensions.w = DOC_WIDTH[index];
            dimensions.h = DOC_HEIGHT[index];
            doc.attributes.add(dimensions);
            if ("application/x-tgsticker".equals(DOC_MIME[index])) {
                doc.attributes.add(new TLRPC.TL_documentAttributeAnimated());
            }
            CACHE.put(doc.id, doc);
            return doc;
        } catch (RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Имя ассета по id документа — для {@code upload.getFile}. */
    static String assetName(long id) {
        return FILES.get(id);
    }

    /** Файл ассета на диске (копируется из assets при первом обращении). */
    static synchronized File assetFile(String name) throws IOException {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "waysgram_reactions_v1");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Reaction cache unavailable");
        }
        File file = new File(dir, name.replace('/', '_'));
        if (!file.isFile()) {
            File staging = File.createTempFile("reaction", ".tmp", dir);
            try {
                try (InputStream in = ApplicationLoader.applicationContext.getAssets()
                        .open("waysgram_emoji/" + name);
                     FileOutputStream out = new FileOutputStream(staging)) {
                    byte[] bytes = new byte[8192];
                    int read;
                    while ((read = in.read(bytes)) != -1) {
                        out.write(bytes, 0, read);
                    }
                }
                if (!staging.renameTo(file)) {
                    throw new IOException("Reaction cache write failed");
                }
            } finally {
                staging.delete();
            }
        }
        return file;
    }

    static TLObject getFile(TLRPC.TL_upload_getFile request) throws WaysGramApi.ApiException {
        if (!(request.location instanceof TLRPC.TL_inputDocumentFileLocation)) {
            return null;
        }
        String name = assetName(request.location.id);
        if (name == null) {
            return null;
        }
        try {
            return WaysGramGifts.serveFile(assetFile(name), request);
        } catch (IOException e) {
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        }
    }
}
