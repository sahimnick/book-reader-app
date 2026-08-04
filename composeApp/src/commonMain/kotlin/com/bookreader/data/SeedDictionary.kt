package com.bookreader.data

/**
 * A starter English→Persian dictionary, bundled so the lookup feature works on
 * a fresh install with no network and no extra download.
 *
 * This is deliberately a seed, not a complete dictionary: comprehensive EN–FA
 * lexicons are licensed works and cannot be vendored into an app repository.
 * `DictionaryImporter` loads a full dataset in the same format when you supply
 * one — see the "Dictionary data" section of the README.
 *
 * Format per line, tab-separated:
 *   headword ⇥ part-of-speech ⇥ IPA ⇥ English gloss ⇥ Persian glosses (|) ⇥ examples (|)
 */
internal object SeedDictionary {

    val TSV: String = """
word	noun	/wɜːd/	a single distinct meaningful element of speech	کلمه|واژه|لغت	Choose your words carefully.|He never said a word.
book	noun	/bʊk/	a written or printed work consisting of pages	کتاب|دفتر	She read the book twice.|This book changed my life.
read	verb	/riːd/	to look at and comprehend written matter	خواندن|مطالعه کردن	I read every night before sleeping.
write	verb	/raɪt/	to mark letters or words on a surface	نوشتن|نگاشتن	He writes for a newspaper.
learn	verb	/lɜːn/	to gain knowledge or skill	یاد گرفتن|آموختن	Children learn quickly.
study	verb	/ˈstʌdi/	to devote time to acquiring knowledge	مطالعه کردن|درس خواندن	She studies medicine.
language	noun	/ˈlæŋɡwɪdʒ/	the method of human communication	زبان	English is a global language.
sentence	noun	/ˈsentəns/	a set of words expressing a complete thought	جمله	Write a sentence with this word.
meaning	noun	/ˈmiːnɪŋ/	what is intended to be expressed	معنی|مفهوم	What is the meaning of this word?
translate	verb	/trænzˈleɪt/	to express in another language	ترجمه کردن	Can you translate this page?
morning	noun	/ˈmɔːnɪŋ/	the period from sunrise to noon	صبح|بامداد	The morning was cold and bright.
night	noun	/naɪt/	the period of darkness between days	شب	She works at night.
day	noun	/deɪ/	a period of twenty-four hours	روز	It rained all day.
year	noun	/jɪə/	the time taken by the earth to orbit the sun	سال	He lived there for three years.
time	noun	/taɪm/	the indefinite continued progress of existence	زمان|وقت	We ran out of time.
house	noun	/haʊs/	a building for human habitation	خانه|منزل	They bought a small house.
home	noun	/həʊm/	the place where one lives	خانه|منزل	I want to go home.
city	noun	/ˈsɪti/	a large town	شهر	Tehran is a large city.
country	noun	/ˈkʌntri/	a nation with its own government	کشور|سرزمین	She visited many countries.
road	noun	/rəʊd/	a wide way leading from one place to another	جاده|راه	The road was empty.
water	noun	/ˈwɔːtə/	a colourless transparent liquid	آب	Please bring me water.
fire	noun	/ˈfaɪə/	combustion producing light and heat	آتش	They sat around the fire.
tree	noun	/triː/	a woody perennial plant	درخت	An old tree stood there.
flower	noun	/ˈflaʊə/	the seed-bearing part of a plant	گل	She picked a flower.
child	noun	/tʃaɪld/	a young human being	کودک|بچه|فرزند	The child was sleeping.
man	noun	/mæn/	an adult human male	مرد	An old man walked by.
woman	noun	/ˈwʊmən/	an adult human female	زن	The woman smiled.
friend	noun	/frend/	a person with whom one has a bond	دوست|رفیق	He is my best friend.
family	noun	/ˈfæməli/	a group of related people	خانواده	My family lives abroad.
work	verb	/wɜːk/	to be engaged in physical or mental activity	کار کردن	She works in a hospital.
run	verb	/rʌn/	to move at a speed faster than walking	دویدن|اجرا کردن	He ran to catch the bus.
walk	verb	/wɔːk/	to move at a regular pace on foot	راه رفتن|قدم زدن	They walked home together.
speak	verb	/spiːk/	to say something in order to convey information	صحبت کردن|سخن گفتن	Do you speak Persian?
listen	verb	/ˈlɪsən/	to give attention to sound	گوش دادن	Listen to me carefully.
see	verb	/siː/	to perceive with the eyes	دیدن	I can see the mountain.
think	verb	/θɪŋk/	to have a particular opinion or idea	فکر کردن|اندیشیدن	I think you are right.
know	verb	/nəʊ/	to be aware of through observation	دانستن|شناختن	I know the answer.
understand	verb	/ˌʌndəˈstænd/	to perceive the intended meaning of	فهمیدن|درک کردن	I understand the question.
remember	verb	/rɪˈmembə/	to recall to the mind	به یاد آوردن|خاطر داشتن	I remember that day.
forget	verb	/fəˈɡet/	to fail to remember	فراموش کردن	Don't forget your keys.
begin	verb	/bɪˈɡɪn/	to start	شروع کردن|آغاز کردن	The story begins here.
finish	verb	/ˈfɪnɪʃ/	to bring to an end	تمام کردن|به پایان رساندن	I finished the book.
open	verb	/ˈəʊpən/	to move so as to leave a space	باز کردن	Open the window, please.
close	verb	/kləʊz/	to move so as to cover an opening	بستن	Please close the door.
give	verb	/ɡɪv/	to freely transfer possession	دادن|بخشیدن	Give me the book.
take	verb	/teɪk/	to lay hold of with the hands	گرفتن|بردن	Take this with you.
find	verb	/faɪnd/	to discover by chance or search	پیدا کردن|یافتن	I found my keys.
lose	verb	/luːz/	to be deprived of	گم کردن|باختن	Don't lose hope.
love	verb	/lʌv/	to feel deep affection for	دوست داشتن|عشق ورزیدن	She loves her family.
hope	noun	/həʊp/	a feeling of expectation and desire	امید	There is still hope.
fear	noun	/fɪə/	an unpleasant emotion caused by threat	ترس|بیم	He spoke without fear.
happy	adjective	/ˈhæpi/	feeling or showing pleasure	خوشحال|شاد	She looked happy.
sad	adjective	/sæd/	feeling sorrow	غمگین|ناراحت	He felt sad that evening.
good	adjective	/ɡʊd/	to be desired or approved of	خوب|نیکو	That is a good idea.
bad	adjective	/bæd/	of poor quality	بد	The weather was bad.
big	adjective	/bɪɡ/	of considerable size	بزرگ|درشت	A big house stood there.
small	adjective	/smɔːl/	of a size that is less than normal	کوچک|ریز	She has a small room.
long	adjective	/lɒŋ/	measuring a great distance	بلند|طولانی	It was a long journey.
short	adjective	/ʃɔːt/	measuring a small distance	کوتاه	He gave a short answer.
new	adjective	/njuː/	produced or discovered recently	جدید|نو	I bought a new phone.
old	adjective	/əʊld/	having lived for a long time	قدیمی|پیر|کهنه	An old book lay open.
young	adjective	/jʌŋ/	having lived for only a short time	جوان	The young man smiled.
beautiful	adjective	/ˈbjuːtɪfəl/	pleasing the senses aesthetically	زیبا|قشنگ	What a beautiful morning!
difficult	adjective	/ˈdɪfɪkəlt/	needing much effort	سخت|دشوار|مشکل	This is a difficult question.
easy	adjective	/ˈiːzi/	achieved without great effort	آسان|راحت	The test was easy.
important	adjective	/ɪmˈpɔːtənt/	of great significance	مهم	This is an important point.
strong	adjective	/strɒŋ/	having the power to move heavy weights	قوی|نیرومند	He is very strong.
weak	adjective	/wiːk/	lacking physical strength	ضعیف|ناتوان	She felt weak.
quick	adjective	/kwɪk/	moving fast	سریع|تند	He gave a quick reply.
slow	adjective	/sləʊ/	moving at a low speed	کند|آهسته	Traffic was slow today.
cold	adjective	/kəʊld/	of low temperature	سرد	The morning was cold.
hot	adjective	/hɒt/	having a high temperature	داغ|گرم	The tea is too hot.
light	noun	/laɪt/	the natural agent that makes things visible	نور|روشنایی	Light came through the window.
dark	adjective	/dɑːk/	with little or no light	تاریک|تیره	The room was dark.
white	adjective	/waɪt/	of the colour of milk	سفید	She wore a white dress.
black	adjective	/blæk/	of the darkest colour	سیاه|مشکی	A black cat crossed.
red	adjective	/red/	of the colour of blood	قرمز|سرخ	He drove a red car.
green	adjective	/ɡriːn/	of the colour of grass	سبز	The fields were green.
blue	adjective	/bluː/	of the colour of a clear sky	آبی	The sky was blue.
heart	noun	/hɑːt/	the organ that pumps blood	قلب|دل	My heart was beating fast.
hand	noun	/hænd/	the end part of the arm	دست	He raised his hand.
eye	noun	/aɪ/	the organ of sight	چشم	She closed her eyes.
head	noun	/hed/	the upper part of the body	سر	He shook his head.
voice	noun	/vɔɪs/	the sound produced in a person's larynx	صدا|آوا	Her voice was calm.
story	noun	/ˈstɔːri/	an account of imaginary or real events	داستان|قصه	Tell me a story.
question	noun	/ˈkwestʃən/	a sentence worded so as to elicit information	سوال|پرسش	May I ask a question?
answer	noun	/ˈɑːnsə/	a response to a question	جواب|پاسخ	I know the answer.
problem	noun	/ˈprɒbləm/	a matter regarded as unwelcome	مشکل|مسئله	We solved the problem.
idea	noun	/aɪˈdɪə/	a thought or suggestion	ایده|فکر|نظر	That is a good idea.
reason	noun	/ˈriːzən/	a cause or explanation	دلیل|علت	Give me one reason.
truth	noun	/truːθ/	that which is true	حقیقت|راستی	Tell me the truth.
life	noun	/laɪf/	the condition that distinguishes living organisms	زندگی|عمر	Life is short.
death	noun	/deθ/	the end of life	مرگ	He feared death.
world	noun	/wɜːld/	the earth with all its inhabitants	جهان|دنیا	The world is changing.
school	noun	/skuːl/	an institution for educating children	مدرسه	She goes to school.
teacher	noun	/ˈtiːtʃə/	a person who teaches	معلم|آموزگار	Our teacher is kind.
student	noun	/ˈstjuːdənt/	a person studying at a school	دانش‌آموز|دانشجو	He is a good student.
money	noun	/ˈmʌni/	a medium of exchange	پول	She saved money.
food	noun	/fuːd/	any nutritious substance eaten	غذا|خوراک	The food was delicious.
sleep	verb	/sliːp/	to rest with eyes closed	خوابیدن	I slept for eight hours.
dream	noun	/driːm/	a series of images occurring in sleep	رویا|خواب	She had a strange dream.
travel	verb	/ˈtrævəl/	to make a journey	سفر کردن	They travel every summer.
happen	verb	/ˈhæpən/	to take place	اتفاق افتادن|رخ دادن	What happened here?
change	verb	/tʃeɪndʒ/	to make or become different	تغییر دادن|عوض کردن	Things change quickly.
build	verb	/bɪld/	to construct by putting parts together	ساختن|بنا کردن	We shall build it.
break	verb	/breɪk/	to separate into pieces	شکستن	Don't break the glass.
carry	verb	/ˈkæri/	to support and move from one place to another	حمل کردن|بردن	She carried the bags.
follow	verb	/ˈfɒləʊ/	to go or come after	دنبال کردن|پیروی کردن	Nobody followed him.
watch	verb	/wɒtʃ/	to look at attentively	تماشا کردن|نگاه کردن	They watched the sunset.
wait	verb	/weɪt/	to stay where one is until something happens	منتظر ماندن|صبر کردن	Please wait here.
ask	verb	/ɑːsk/	to say something to get an answer	پرسیدن|درخواست کردن	He asked for help.
call	verb	/kɔːl/	to give a name to, or to telephone	صدا زدن|تماس گرفتن	Call me tomorrow.
help	verb	/help/	to make it easier for someone to do something	کمک کردن|یاری کردن	Can you help me?
need	verb	/niːd/	to require because it is essential	نیاز داشتن|لازم داشتن	I need more time.
want	verb	/wɒnt/	to have a desire for	خواستن	What do you want?
try	verb	/traɪ/	to make an attempt	تلاش کردن|سعی کردن	Try again tomorrow.
keep	verb	/kiːp/	to have or retain possession of	نگه داشتن|حفظ کردن	Keep the change.
leave	verb	/liːv/	to go away from	ترک کردن|رفتن	She left early.
stay	verb	/steɪ/	to remain in the same place	ماندن	Stay with me.
stop	verb	/stɒp/	to cease moving or operating	ایستادن|متوقف کردن	The car stopped.
turn	verb	/tɜːn/	to move in a circular direction	چرخیدن|برگرداندن	Turn left at the corner.
bring	verb	/brɪŋ/	to take or go with to a place	آوردن	Bring me the book.
send	verb	/send/	to cause to go to a destination	فرستادن|ارسال کردن	I sent a letter.
grow	verb	/ɡrəʊ/	to increase in size	رشد کردن|بزرگ شدن	The tree grew tall.
teach	verb	/tiːtʃ/	to impart knowledge	یاد دادن|آموزش دادن	She teaches English.
sell	verb	/sel/	to give in exchange for money	فروختن	They sell books here.
buy	verb	/baɪ/	to acquire in exchange for money	خریدن	I bought a new book.
pay	verb	/peɪ/	to give money in return for goods	پرداختن|پول دادن	He paid the bill.
play	verb	/pleɪ/	to engage in activity for enjoyment	بازی کردن|نواختن	Children play outside.
sing	verb	/sɪŋ/	to make musical sounds with the voice	آواز خواندن	She sang beautifully.
laugh	verb	/lɑːf/	to make sounds expressing amusement	خندیدن	Everyone laughed.
cry	verb	/kraɪ/	to shed tears	گریه کردن	The baby cried.
smile	verb	/smaɪl/	to form one's features into a pleased expression	لبخند زدن	He smiled at me.
hold	verb	/həʊld/	to grasp and keep	نگه داشتن|گرفتن	Hold my hand.
sit	verb	/sɪt/	to rest with the body supported by the buttocks	نشستن	They sat by the fire.
stand	verb	/stænd/	to be in an upright position	ایستادن	He stood at the door.
lie	verb	/laɪ/	to be in a horizontal position	دراز کشیدن|دروغ گفتن	The book lay open.
put	verb	/pʊt/	to move to a particular position	گذاشتن|قرار دادن	Put it on the table.
become	verb	/bɪˈkʌm/	to begin to be	شدن|گشتن	It became clear.
seem	verb	/siːm/	to give the impression of being	به نظر رسیدن	She seems tired.
feel	verb	/fiːl/	to experience an emotion or sensation	احساس کردن	I feel better now.
mean	verb	/miːn/	to intend to convey	معنی دادن|قصد داشتن	What does this mean?
show	verb	/ʃəʊ/	to allow to be seen	نشان دادن	Show me the way.
tell	verb	/tel/	to communicate information	گفتن|تعریف کردن	Tell me everything.
say	verb	/seɪ/	to utter words	گفتن	He said nothing.
look	verb	/lʊk/	to direct one's gaze	نگاه کردن	Look at the sky.
meet	verb	/miːt/	to come into the presence of	ملاقات کردن|دیدار کردن	They met at the station.
move	verb	/muːv/	to go in a specified direction	حرکت کردن|جابجا شدن	Don't move.
believe	verb	/bɪˈliːv/	to accept as true	باور کردن|اعتقاد داشتن	I believe you.
decide	verb	/dɪˈsaɪd/	to come to a resolution	تصمیم گرفتن	She decided to leave.
explain	verb	/ɪkˈspleɪn/	to make clear by describing	توضیح دادن|شرح دادن	Explain it again.
consider	verb	/kənˈsɪdə/	to think carefully about	در نظر گرفتن|ملاحظه کردن	Consider the options.
appear	verb	/əˈpɪə/	to come into sight	ظاهر شدن|به نظر رسیدن	A light appeared.
allow	verb	/əˈlaʊ/	to let someone do something	اجازه دادن	Allow me to help.
create	verb	/kriˈeɪt/	to bring into existence	ایجاد کردن|خلق کردن	He created a new system.
develop	verb	/dɪˈveləp/	to grow or cause to grow	توسعه دادن|پرورش دادن	They develop software.
improve	verb	/ɪmˈpruːv/	to make or become better	بهبود بخشیدن|بهتر کردن	Practice improves memory.
increase	verb	/ɪnˈkriːs/	to become greater in size or amount	افزایش دادن|زیاد شدن	Prices increased.
reduce	verb	/rɪˈdjuːs/	to make smaller or less	کاهش دادن|کم کردن	Reduce the noise.
continue	verb	/kənˈtɪnjuː/	to persist in an activity	ادامه دادن	Continue reading.
return	verb	/rɪˈtɜːn/	to come or go back	بازگشتن|برگرداندن	He returned home late.
arrive	verb	/əˈraɪv/	to reach a destination	رسیدن|وارد شدن	They arrived at nine.
enter	verb	/ˈentə/	to come or go into	وارد شدن|داخل شدن	She entered the room.
choose	verb	/tʃuːz/	to pick out as being the best	انتخاب کردن|برگزیدن	Choose one word.
add	verb	/æd/	to join to something else	اضافه کردن|افزودن	Add this word to the cards.
save	verb	/seɪv/	to keep safe or rescue	ذخیره کردن|نجات دادن	Save your work.
review	verb	/rɪˈvjuː/	to examine or assess again	مرور کردن|بازبینی کردن	Review these cards daily.
practice	noun	/ˈpræktɪs/	repeated exercise to improve skill	تمرین|ممارست	Practice makes perfect.
memory	noun	/ˈmeməri/	the faculty of storing and recalling information	حافظه|خاطره	She has a good memory.
knowledge	noun	/ˈnɒlɪdʒ/	facts and skills acquired by experience	دانش|معرفت	Knowledge is power.
page	noun	/peɪdʒ/	one side of a sheet of paper in a book	صفحه|برگ	Turn to the next page.
chapter	noun	/ˈtʃæptə/	a main division of a book	فصل|بخش	The first chapter is short.
title	noun	/ˈtaɪtəl/	the name of a book or work	عنوان|نام	What is the title?
author	noun	/ˈɔːθə/	a writer of a book	نویسنده|مؤلف	Who is the author?
library	noun	/ˈlaɪbrəri/	a collection of books	کتابخانه	The library was quiet.
    """.trimIndent()
}
