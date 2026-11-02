# SPDX-License-Identifier: AGPL-3.0-or-later
"""Candidate visual food classes and where their training images come from.

One line per class:  id ; English name ; Spanish name hint ; group ; rank ; Open Images labels ; Wikimedia
Commons categories ; flags

- Open Images labels are DisplayNames from oidv7-class-descriptions.csv (resolved to MIDs by
  select_classes.py). Several fine-grained labels are merged into one class when they look the same or have
  near-identical nutrients.
- Commons categories: "Name" or "Name|depth" (subcategory depth, default 1).
- rank decides the label of an Open Images photo with several positive labels: the class with the highest
  rank wins (1 = ingredient, 2 = single food/product, 3 = dish, 4 = specific dish); a tie at the top rank
  makes the photo ambiguous and it is not used.
- flags: "food" = only use Open Images photos of this label that are also verified as Food/Dish/... (the
  label alone also covers live animals, plants, logos...).
Classes left with fewer than 150 training photos are dropped by build_dataset.py, which writes the final
list (classes.csv).
"""

_TABLE = """
apple;Apple;manzana;fruit;1;Apple,Granny smith,Mcintosh;Apples as food|1;
banana;Banana;plátano;fruit;1;Banana;Bananas as food|1;
orange;Orange;naranja;fruit;1;Orange (fruit),Valencia orange,Bitter orange,Blood orange;Oranges as food|1;
mandarin;Mandarin;mandarina;fruit;1;Mandarin orange,Clementine,Tangerine,Tangelo;Mandarin oranges as food|1,Clementines|1,Tangerines|1;
lemon;Lemon;limón;fruit;1;Lemon (plant),Meyer lemon,Sweet lemon;Lemons as food|1;
lime;Lime;lima;fruit;1;Lime (Fruit),Key lime,Persian lime;Limes|1;
grapefruit;Grapefruit;pomelo;fruit;1;Grapefruit,Pomelo;Grapefruits|1,Pomelos as food|1,Grapefruit as food|1;
strawberry;Strawberries;fresas;fruit;1;Strawberry,Strawberries,Virginia strawberry,Alpine strawberry;Strawberries as food|1;
grapes;Grapes;uvas;fruit;1;Grape;Grapes as food|1;
watermelon;Watermelon;sandía;fruit;1;Watermelon;Watermelons as food|1;
melon;Melon;melón;fruit;1;Melon,Cantaloupe,Muskmelon,Honeydew;Melons as food|1,Honeydew|0;
pineapple;Pineapple;piña;fruit;1;Pineapple,Ananas;Pineapples as food|1;
kiwi;Kiwi;kiwi;fruit;1;Kiwifruit;Kiwifruits as food|1;
pear;Pear;pera;fruit;1;Pear,European pear;Pears as food|1;
peach;Peach / nectarine;melocotón / nectarina;fruit;1;Peach,Nectarine (Fruit);Peaches|1,Nectarines|1;
cherries;Cherries;cerezas;fruit;1;Cherry;Cherries as food|1;
blueberries;Blueberries;arándanos;fruit;1;Blueberry,Bilberry,Huckleberry;Blueberries as food|1;
raspberries;Raspberries;frambuesas;fruit;1;Raspberry;Raspberries|1;
blackberries;Blackberries;moras;fruit;1;Blackberry (Plant),Boysenberry,Dewberry,Olallieberry,Loganberry,Tayberry;Blackberries as food|1;
mango;Mango;mango;fruit;1;Mango;Mango as food|1;
avocado;Avocado;aguacate;fruit;1;Avocado;Avocado as food|1;
pomegranate;Pomegranate;granada;fruit;1;Pomegranate;Pomegranates as food|1;
papaya;Papaya;papaya;fruit;1;Papaya;Papaya|1;
apricot;Apricot;albaricoque;fruit;1;Apricot;Apricots|1;
plum;Plum;ciruela;fruit;1;Plum (Fruit);Plums|1;
fig;Fig;higo;fruit;1;Common fig;Figs|1;
coconut;Coconut;coco;fruit;1;Coconut;Coconuts as food|1;food
dragon_fruit;Dragon fruit;pitahaya;fruit;1;Pitaya,Pitahaya,Costa Rican pitahaya;Pitaya|1;
persimmon;Persimmon;caqui;fruit;1;Persimmon;Persimmon|1;
lychee;Lychee;lichi;fruit;1;Lychee;Lychees|1;
passion_fruit;Passion fruit;maracuyá;fruit;1;Passion fruit,Passionfruit;Passionfruits as food|1,Passion fruit|1;
dates;Dates;dátiles;fruit;1;Date palm;;food
raisins;Raisins;pasas;fruit;1;Raisin,Sultana;Raisins|1;
dried_fruit;Dried fruit;fruta deshidratada;fruit;2;Dried fruit;Dried fruit|1;
quince_paste;Quince paste;dulce de membrillo;sweets;2;Quince cheese;Quince cheese,Dulce de membrillo;
tomato;Tomato;tomate;vegetables;1;Tomato,Plum tomato,Bush tomato,Cherry tomato;;
cucumber;Cucumber;pepino;vegetables;1;Cucumber;;
carrot;Carrot;zanahoria;vegetables;1;Carrot,Baby carrot;;
broccoli;Broccoli;brócoli;vegetables;1;Broccoli;Broccoli as food|1;
cauliflower;Cauliflower;coliflor;vegetables;1;Cauliflower,Broccoflower;Cauliflower|1;
lettuce;Lettuce / salad leaves;lechuga / hojas de ensalada;vegetables;1;Lettuce,Iceberg lettuce,Arugula;Lettuce|1;
spinach;Spinach;espinacas;vegetables;1;Spinach;Spinach|1;
chard;Chard;acelgas;vegetables;1;Chard;Chard|1;
cabbage;Cabbage;col / repollo;vegetables;1;Cabbage,Red cabbage;Cabbage|1;
bell_pepper;Bell pepper;pimiento;vegetables;1;Bell pepper,Red bell pepper,Green bell pepper,Yellow pepper,Italian sweet pepper,Pimiento;;
chili_pepper;Chili pepper;guindilla / chile;vegetables;1;Chili pepper,Bird's eye chili,Cayenne pepper,Serrano pepper,Jalapeño,Habanero chili,Peperoncino,Malagueta pepper,Chile de árbol,Tabasco pepper;;
onion;Onion;cebolla;vegetables;1;Onion,Red onion,Yellow onion,Shallot;;
spring_onion;Spring onion;cebolleta;vegetables;1;Scallion,Welsh onion;Scallions|1;
garlic;Garlic;ajo;vegetables;1;Garlic;Garlics as food|1;
leek;Leek;puerro;vegetables;1;Leek;;
potato;Potato;patata;vegetables;1;Potato,Russet burbank potato,Yukon gold potato,Fingerling potato,Creamer potato;;
sweet_potato;Sweet potato;boniato;vegetables;1;Sweet potato,Baked sweet potato;Sweet potatoes|1,Baked sweet potatoes;
pumpkin;Pumpkin;calabaza;vegetables;1;Pumpkin,Winter squash,Calabaza,Butternut squash;;food
zucchini;Courgette;calabacín;vegetables;1;Zucchini,Summer squash;;
eggplant;Aubergine;berenjena;vegetables;1;Eggplant;Eggplant|1;
corn;Corn on the cob;mazorca de maíz;vegetables;1;Corn on the cob,Sweet corn,Elote,Corn,Corn kernel;;
mushrooms;Mushrooms;champiñones / setas;vegetables;1;Champignon mushroom,Shiitake,Edible mushroom,Agaricus,Mushroom;;food
asparagus;Asparagus;espárragos;vegetables;1;Asparagus,Garden Asparagus;;
green_beans;Green beans;judías verdes;vegetables;1;Green bean;Green beans|1;
peas;Peas;guisantes;vegetables;1;Pea,Snap pea,Snow Peas;Peas|1;
artichoke;Artichoke;alcachofa;vegetables;1;Artichoke;Artichokes as food|1;
brussels_sprouts;Brussels sprouts;coles de Bruselas;vegetables;1;Brussels sprout;Brussels sprouts as food|1;
beetroot;Beetroot;remolacha;vegetables;1;Beetroot,Beet;Beetroot|1;
radish;Radish;rábano;vegetables;1;Radish;Radishes|1;
kale;Kale;col rizada (kale);vegetables;1;Kale;;
celery;Celery;apio;vegetables;1;Celery;Celery as food|1,Celery|0;
sauerkraut;Sauerkraut;chucrut;vegetables;2;Sauerkraut;Sauerkraut|1;
grilled_vegetables;Grilled vegetables;verduras a la plancha;dishes;3;;Grilled vegetables|1,Escalivada;
olives;Olives;aceitunas;fats_nuts;1;Olive;Olives as food|1;food
edamame;Edamame;edamame;legumes;1;Edamame;Edamame|0;
chickpeas;Chickpeas;garbanzos;legumes;1;Chickpea;;
beans;Beans;alubias;legumes;1;Bean,Common bean,Navy bean;;
lentils;Lentil stew / dal;lentejas;legumes;4;Lentil,Dal,Dal makhani;Lentil soups|2,Lentils (food)|1,Dal|1;
bean_stew;Bean stew (fabada);fabada / potaje de alubias;dishes;4;Fabada asturiana,Soup beans,Feijoada;Fabada,Bean stews|1;
baked_beans;Baked beans;alubias con tomate;legumes;3;Baked beans;Baked beans|1;
hummus;Hummus;hummus;sauces;3;Hummus;Hummus|1;
tofu;Tofu;tofu;legumes;2;Tofu;Tofu|1;
bread;Bread;pan;grains;1;Bread,Loaf,Plain loaf,White bread,Sourdough,Ciabatta,Sliced bread;;
wholemeal_bread;Wholemeal bread;pan integral;grains;2;Whole wheat bread,Multigrain bread,Rye bread,Brown bread,Graham bread;;
baguette;Baguette;barra de pan;grains;3;Baguette;Baguettes|1;
bread_roll;Bread roll;panecillo;grains;2;Bread roll,Bun (Food),Kaiser roll,Breakfast roll;;
toast;Toast;tostada;grains;2;Toast,Texas toast;Toast|1;
avocado_toast;Avocado toast;tostada de aguacate;dishes;4;Avocado toast;Avocado toast|1;
croissant;Croissant;cruasán;sweets;3;Croissant;Croissants|1;
pain_au_chocolat;Pain au chocolat;napolitana de chocolate;sweets;4;Pain au chocolat;Pains au chocolat|0;
bagel;Bagel;bagel;grains;2;Bagel;Bagels|1;
pretzel;Pretzel;pretzel;snacks;2;Pretzel;Pretzels|1;
flatbread;Flatbread / naan;pan plano / naan;grains;2;Naan,Flatbread,Chapati,Roti,Lavash,Pita,Piadina;;
tortilla_wrap;Flour or corn tortilla;tortilla de trigo / maíz;grains;2;Tortilla,Corn tortilla;Tortillas|1;
focaccia;Focaccia;focaccia;grains;2;Focaccia;Focaccia|1;
rice;White rice;arroz blanco;grains;1;Rice,Steamed rice,White rice,Jasmine rice,Basmati,Glutinous rice;;
brown_rice;Brown rice;arroz integral;grains;2;Brown rice;Brown rice|0;
pasta;Pasta;pasta;grains;3;Pasta,Spaghetti,Penne,Fusilli,Macaroni,Rigatoni,Linguine,Fettuccine,Tagliatelle,Pappardelle,Bucatini,Capellini,Al dente,Spaghetti aglio e olio,Mostaccioli,Taglierini,Trenette,Bigoli,Pici,Scialatelli,Stringozzi;;
noodles;Noodles (stir-fried);fideos salteados;grains;3;Noodle,Chinese noodles,Chow mein,Lo mein,Fried noodles,Yakisoba,Mie goreng,Hokkien mee,Char kway teow,Singapore-style noodles,Pancit,Bakmi,Yi mein,Rice noodles,Rice vermicelli,Shahe fen,Yaki udon;;
oatmeal;Porridge;gachas de avena;grains;3;Oatmeal,Steel-cut oats;Oatmeal|1,Porridges|0;
cereal;Breakfast cereal / muesli;cereales / muesli;grains;2;Breakfast cereal,Corn flakes,Muesli,Granola;;
couscous;Couscous;cuscús;grains;3;Couscous;Couscous|1;
popcorn;Popcorn;palomitas;snacks;2;Popcorn,Kettle corn;;
crackers;Crackers;galletas saladas;snacks;2;Cracker,Water biscuit;Crackers (food)|1;
fried_rice;Fried rice;arroz frito;dishes;4;Fried rice,Yeung chow fried rice,Thai fried rice,Nasi goreng;Fried rice|1;
risotto;Risotto;risotto;dishes;5;Risotto;Risotto|1;
paella;Paella;paella;dishes;5;Paella,Arroz a la valenciana;Paella|2;
pilaf;Pilaf / biryani;arroz pilaf / biryani;dishes;4;Pilaf,Biryani,Spiced rice,Kabsa,Jollof rice,Saffron rice;;
egg;Eggs;huevos;eggs;1;Egg;;food
fried_egg;Fried egg;huevo frito;eggs;3;Fried egg;Fried eggs|1;
boiled_egg;Boiled egg;huevo cocido;eggs;2;Boiled egg;Boiled eggs|1;
scrambled_eggs;Scrambled eggs;huevos revueltos;eggs;3;Scrambled eggs;Scrambled eggs|1;
omelette;Omelette;tortilla francesa;eggs;3;Omelette,Indian omelette,Tomato omelette;Omelettes|1;
spanish_omelette;Spanish omelette;tortilla de patatas;eggs;5;Tortilla de patatas;Spanish omelette|2;
poached_egg;Poached egg;huevo poché;eggs;3;Poached egg;Poached eggs|1;
eggs_benedict;Eggs Benedict;huevos Benedict;dishes;4;Eggs benedict,Eggs sardou;;
deviled_eggs;Deviled eggs;huevos rellenos;eggs;3;Deviled egg;Deviled eggs|1;
steak;Steak;filete / chuletón;meat;3;Steak,Sirloin steak,Rib eye steak,Delmonico steak,Flat iron steak,Beef tenderloin,Kobe beef,Wagyu beef,Matsusaka beef,Steak frites;Entrecôte;
grilled_chicken;Grilled chicken;pollo a la plancha;meat;2;Chicken meat,Chicken breast,Chicken thighs,Barbecue chicken;Grilled chicken|1;
roast_chicken;Roast chicken;pollo asado;meat;3;Hendl;Roast chicken|1;
fried_chicken;Fried chicken;pollo frito;meat;3;Fried chicken,Crispy fried chicken,Karaage,Chicken tatsuta;Fried chicken|1,Karaage|1;
chicken_wings;Chicken wings;alitas de pollo;meat;3;Buffalo wing;Chicken wings as food|1,Buffalo wings|1;
chicken_nuggets;Chicken nuggets;nuggets de pollo;fast_food;3;Chicken nugget,Chicken fingers,Chicken fries;Chicken nuggets|1;
tandoori_chicken;Tandoori chicken;pollo tandoori;dishes;4;Tandoori chicken,Chicken tikka;;
sweet_sour_chicken;Sweet and sour chicken;pollo agridulce;dishes;4;Sweet and sour chicken,General tso's chicken,Orange chicken,Sesame chicken,Lemon chicken;;
stir_fry;Stir-fry;salteado (wok);dishes;3;Stir frying,Chop suey,Kung pao chicken,Mongolian beef;;
pork_chop;Pork chop;chuleta de cerdo;meat;3;Pork chop,Pork steak,Pork loin;Pork chops|1;
ribs;Ribs;costillas;meat;3;Ribs,Spare ribs,Pork ribs,Short ribs;Pork ribs as food|1,Ribs (food)|1;
sausages;Sausages;salchichas;meat;2;Sausage,Bratwurst,Kielbasa,Breakfast sausage,Italian sausage,Knackwurst,Thuringian sausage,Bockwurst,Frankfurter würstchen,Vienna sausage,Cumberland sausage,Lincolnshire sausage,Weisswurst,Andouille,Boerewors,Debrecener,Loukaniko,Bierwurst;;
hot_dog;Hot dog;perrito caliente;fast_food;3;Hot dog,Chili dog,Chicago-style hot dog,Coney island hot dog,Michigan hot dog,Dodger dog;;
bacon;Bacon;beicon;meat;2;Bacon,Back bacon,Turkey bacon;Bacon|1;
ham;Cooked ham;jamón cocido;meat;2;Ham,Turkey ham;;
cured_ham;Cured ham;jamón serrano;meat;3;Jamón serrano,Jamón,Prosciutto,Bayonne ham,Tyrolean speck,Crudo,Bresaola;Jamón|1;
chorizo;Chorizo;chorizo;meat;3;Chorizo,Chistorra;Chorizo|1;
salami;Salami / salchichón;salchichón / fuet;meat;2;Salami,Salchichón,Fuet,Soppressata,Salumi;Salami|1,Salchichón,Fuet;
cold_cuts;Charcuterie board;tabla de embutidos;meat;3;Charcuterie,Cold cut;Charcuterie boards|1;
black_pudding;Blood sausage;morcilla;meat;2;Black pudding,Kaszanka;Blood sausages|1;
meatballs;Meatballs;albóndigas;meat;3;Meatball,Frikadeller,Kofta;Meatballs|1,Albóndigas|1;
breaded_cutlet;Breaded cutlet / schnitzel;filete empanado;meat;3;Schnitzel,Cotoletta,Milanesa,Tonkatsu,Cutlet;Cachopo|1,Flamenquín;
roast_beef;Roast beef;rosbif / asado;meat;3;Roast beef,Pot roast,Rinderbraten,Sauerbraten,Brisket;;
pulled_pork;Pulled pork;cerdo desmechado;meat;3;Pulled pork,Carnitas;Pulled pork|1;
kebab;Kebab / shawarma;kebab;fast_food;3;Kebab,Gyro,Shawarma,Doner kebab;Kebabs|1,Shawarma|1,Gyros;
skewers;Meat skewers;brochetas / pinchos morunos;meat;3;Brochette,Skewer,Shashlik,Souvlaki,Satay,Yakitori,Arrosticini,Anticucho,Suya,Shish taouk;;
lamb;Lamb;cordero;meat;2;Lamb and mutton;Lamb and mutton dishes|1;
duck;Roast duck;pato asado;meat;3;Peking duck,Duck meat,Roast goose,Pressed duck;;
turkey;Roast turkey;pavo asado;meat;2;Turkey meat;Roast turkey|1;
pork_belly;Pork belly;panceta;meat;2;Pork belly,Samgyeopsal;Pork belly dishes|1;
suckling_pig;Roast suckling pig;cochinillo asado;meat;4;Suckling pig,Lechon,Pig roast;Cochinillo asado;
mixed_grill;Mixed grill;parrillada de carne;meat;3;Mixed grill,Churrasco food,Barbecue;;
korean_bbq;Korean barbecue;barbacoa coreana;dishes;4;Bulgogi,Galbi,Yakiniku;;
tripe_stew;Tripe stew;callos;dishes;4;Tripe soup;Callos|1,Tripe soups|1;
carpaccio;Carpaccio;carpaccio;meat;3;Carpaccio;Carpaccio (food)|1,Beef carpaccio|0;
salmon;Salmon;salmón;fish;2;Salmon (Food);Salmon as food|1,Salmon dishes|1;
smoked_salmon;Smoked salmon;salmón ahumado;fish;3;Smoked salmon,Lox;;
grilled_fish;Grilled fish;pescado a la plancha;fish;2;Fish (Food),Fish slice (food);Grilled fish|1;food
fried_fish;Fried fish;pescado frito;fish;3;Fried fish,Pescado frito,Fish fry;Fried fish|1;
fish_and_chips;Fish and chips;pescado con patatas fritas;fast_food;4;Fish and chips;;
prawns;Prawns;gambas / langostinos;fish;2;Shrimp,Prawn,Botan shrimp,Caridean shrimp,Scampi,Scampi shrimp,Fried prawn;Gambas al ajillo;food
mussels;Mussels;mejillones;fish;2;Mussel;Mussels as food|1,Mejillones|1;food
clams;Clams;almejas;fish;2;Clam,Fried clams;Clam dishes|1;food
oysters;Oysters;ostras;fish;2;Oyster,Oysters rockefeller;;food
lobster;Lobster;bogavante / langosta;fish;2;Lobster,American lobster,Homarus;;food
crab;Crab;cangrejo / centollo;fish;2;Crab,Crab meat,King crab,Dungeness crab;;food
calamari;Calamari;calamares;fish;4;Squid (Food);Calamari|1,Calamares a la romana|1,Fried squid|1,Squid as food|1,Squid dishes|1;
octopus;Octopus (pulpo a la gallega);pulpo;fish;3;Octopus;Octopus as food|1,Polbo á feira|1;food
sushi;Sushi;sushi;fish;4;Sushi,California roll,Gimbap;;
sashimi;Sashimi;sashimi;fish;4;Sashimi;;
sardines;Sardines;sardinas;fish;2;Sardines,Sardine;Sardine dishes|1;food
anchovies;Anchovies;boquerones / anchoas;fish;2;Anchovy (food),Anchovy;Boquerones en vinagre,Anchovy dishes|1,Anchovies as food|1;food
ceviche;Ceviche;ceviche;dishes;4;Ceviche;Ceviche|1;
cheese;Cheese;queso;dairy;1;Cheese,Cheddar cheese,Gruyère cheese,Gouda cheese,Sheep milk cheese,Processed cheese,American cheese,Havarti Cheese,Colby cheese,Toma cheese,Limburger cheese,Caerphilly cheese,Montasio,Swiss cheese,Muenster cheese,Provolone;Manchego cheese|1;
mozzarella;Mozzarella;mozzarella;dairy;3;Mozzarella;Mozzarella|1;
parmesan;Parmesan;parmesano;dairy;2;Parmigiano-reggiano,Grana padano,Pecorino romano,Romano cheese;;
feta;Feta;queso feta;dairy;2;Feta;Feta|1;
blue_cheese;Blue cheese;queso azul;dairy;2;Blue cheese;Blue cheese|1;
brie;Brie / camembert;brie / camembert;dairy;2;Brie,Camembert Cheese;Brie|1,Camembert|1;
goat_cheese;Goat cheese;queso de cabra;dairy;2;Goat cheese;Goat cheeses|1;
cream_cheese;Cream cheese;queso crema;dairy;2;Cream cheese,Cheese spread,Quark cheese,Fromage blanc;Cream cheese|1;
cottage_cheese;Cottage cheese / ricotta;requesón;dairy;2;Cottage cheese,Ricotta;Cottage cheese|1,Ricotta|1;
yogurt;Yogurt;yogur;dairy;2;Yogurt,Strained yogurt,Skyr,Soy yogurt;Yogurt|1;
milk;Milk;leche;dairy;2;Milk,Skim Milk;;
butter;Butter;mantequilla;dairy;2;Butter,Unsalted butter;Butter|1;
ice_cream;Ice cream;helado;sweets;2;Ice cream,Gelato,Vanilla ice cream,Chocolate ice cream,Soft Serve Ice Creams,Ice cream cone,Dondurma,Frozen yogurt,Soy ice cream,Pistachio ice cream,Sundae;;
ice_pop;Ice lolly;polo (helado);sweets;3;Ice pop,Ice cream bar;Ice cream bars|1;
sorbet;Sorbet / slush;sorbete / granizado;sweets;3;Sorbet,Sherbet,Italian ice,Grattachecca,Slush;;
nuts;Mixed nuts;frutos secos;fats_nuts;1;Nut (Food),Nuts & seeds;;
almonds;Almonds;almendras;fats_nuts;1;Almond;;
walnuts;Walnuts;nueces;fats_nuts;1;Walnut;;
peanuts;Peanuts;cacahuetes;fats_nuts;1;Peanut;;
cashews;Cashews;anacardos;fats_nuts;1;Cashew;;
pistachios;Pistachios;pistachos;fats_nuts;1;Pistachio;;
hazelnuts;Hazelnuts;avellanas;fats_nuts;1;Hazelnut;;
chestnuts;Chestnuts;castañas;fats_nuts;1;Chestnut;;food
peanut_butter;Peanut butter;crema de cacahuete;fats_nuts;2;Peanut butter,Nut butter,Peanut paste;Peanut butter|1;
cake;Cake;tarta / pastel;sweets;3;Cake,Birthday cake,Torte,Kuchen,Red velvet cake;;
chocolate_cake;Chocolate cake;tarta de chocolate;sweets;4;Chocolate cake,Flourless chocolate cake,Sachertorte,Black forest cake,Torta caprese;;
cheesecake;Cheesecake;tarta de queso;sweets;5;Cheesecake;Cheesecakes|1,Basque cheesecake|0;
carrot_cake;Carrot cake;tarta de zanahoria;sweets;4;Carrot cake;Carrot cakes|1;
sponge_cake;Sponge cake;bizcocho;sweets;4;Sponge cake;Sponge cakes|1;
cupcake;Cupcake;cupcake;sweets;3;Cupcake;;
muffin;Muffin;magdalena / muffin;sweets;3;Muffin;Magdalenas;
donut;Doughnut;dónut / berlina;sweets;3;Doughnut,Cider doughnut,Pączki,Ciambella;Rosquillas|1;
churros;Churros;churros;sweets;4;Churro;Churros|1;
cookie;Biscuits / cookies;galletas;sweets;2;Cookie,Chocolate chip cookie,Biscuit,Cookies and crackers,Peanut butter cookie,Oatmeal-raisin cookies,Oatmeal cookie,Snickerdoodle,Sandwich cookie,Oreo,Anzac biscuit,Ginger nut,Biscotti;;
brownie;Brownie;brownie;sweets;3;Chocolate brownie;Brownies|1;
chocolate;Chocolate;chocolate;sweets;2;Chocolate,Chocolate bar;;
bonbons;Chocolates / truffles;bombones;sweets;3;Chocolate truffle,Bonbon,Praline,Chocolate balls,Rum ball,Bourbon ball,Honmei choco,Giri choco;;
candy;Sweets / candy;golosinas / caramelos;sweets;2;Candy,Gummy candy,Hard candy,Lollipop,Wine gum,Marshmallow;;
fruit_pie;Sweet pie;pastel dulce (pie);sweets;3;Pie,Apple pie,Cherry pie,Blueberry pie,Strawberry pie,Pecan pie,Key lime pie,Lemon meringue pie;;
pumpkin_pie;Pumpkin pie;pastel de calabaza;sweets;4;Pumpkin pie,Sweet potato pie;Pumpkin pies|1;
tart;Fruit tart;tarta de fruta;sweets;3;Tart,Crostata,Linzer torte,Tarte tatin;;
custard_tart;Custard tart (pastel de nata);pastel de nata;sweets;4;Custard tart,Egg tart,Custard pie;Pastéis de nata|1,Egg tarts|1;
danish_pastry;Danish pastry;pastel danés;sweets;2;Danish pastry;Danish pastry|1;
cinnamon_roll;Cinnamon roll;rollo de canela;sweets;2;Cinnamon roll,Sweet Rolls;Cinnamon rolls|1;
brioche;Brioche;brioche / bollo;sweets;2;Brioche;Brioche|1;
scones;Scones;scones;sweets;2;Scone;Scones|1;
pancakes;Pancakes;tortitas;sweets;3;Pancake,Pannekoek;;
crepes;Crêpes;crepes;sweets;3;Crêpe;Crêpes|1;
waffles;Waffles;gofres;sweets;3;Waffle,Belgian waffle;;
french_toast;French toast (torrija);torrija;sweets;3;French toast;Torrijas|1,French toast|1;
tiramisu;Tiramisu;tiramisú;sweets;5;Tiramisu;Tiramisu|1;
flan;Crème caramel;flan;sweets;5;Flan;Crème caramel|1;
creme_brulee;Crème brûlée / crema catalana;crema catalana;sweets;5;Crème brûlée;Crema catalana,Crème brûlée|1;
custard;Custard (natillas);natillas;sweets;3;Custard,Bavarian cream;Natillas|1,Custards|1;
mousse;Chocolate mousse / pudding;mousse de chocolate;sweets;3;Mousse,Chocolate pudding;Chocolate mousse|1;
rice_pudding;Rice pudding;arroz con leche;sweets;4;Rice pudding;Arroz con leche|1,Rice puddings|1;
panna_cotta;Panna cotta;panna cotta;sweets;4;Panna cotta;Panna cotta|1;
cream_puffs;Profiteroles / éclairs;profiteroles / pepito;sweets;3;Profiterole,Cream bun;Profiteroles|1,Éclairs|1;
meringue;Meringue / pavlova;merengue / pavlova;sweets;3;Meringue,Pavlova;Meringue|1;
macarons;Macarons;macarons;sweets;3;Macaroon;;
gingerbread;Gingerbread;pan de jengibre;sweets;3;Gingerbread,Lebkuchen;;
apple_strudel;Apple strudel;strudel de manzana;sweets;4;Apple strudel,Strudel;Apple strudel|1;
jelly;Jelly;gelatina;sweets;3;Gelatin dessert;Gelatin desserts|1;
turron;Turrón;turrón;sweets;4;Turrón;Turrón|1;
honey;Honey;miel;sweets;2;Honey;;food
jam;Jam;mermelada;sweets;2;Fruit preserve,Lingonberry jam;Jam|1;
chocolate_spread;Chocolate spread;crema de cacao;sweets;2;Chocolate spread;;
dulce_de_leche;Dulce de leche;dulce de leche;sweets;2;Dulce de leche,Cajeta,Manjar blanco;Dulce de leche|1;
french_fries;French fries;patatas fritas;fast_food;3;French fries,Potato wedges;;
potato_chips;Crisps;patatas fritas de bolsa;snacks;2;Potato chip,Chip;;
tortilla_chips;Tortilla chips;totopos;snacks;2;Tortilla chip,Totopo,Corn chip;Tortilla chips|1;
nachos;Nachos;nachos con queso;fast_food;4;Nachos;Nachos|1;
patatas_bravas;Patatas bravas;patatas bravas;dishes;5;Patatas bravas;Patatas bravas;
spring_rolls;Spring rolls;rollitos de primavera;snacks;3;Spring roll,Egg roll,Lumpia,Nem rán;;
dumplings;Dumplings / gyoza;gyozas / dumplings;dishes;3;Dumpling,Jiaozi,Mandu,Momo,Wonton,Xiaolongbao,Shumai,Dim sim,Buuz,Dim sum;Gyoza|1;
pierogi;Pierogi;pierogi;dishes;3;Pierogi,Varenyky,Pelmeni,Kreplach;Varenyky|1,Pelmeni|1;
steamed_buns;Steamed buns (bao);bao (bollo al vapor);dishes;3;Baozi,Cha siu bao,Nikuman,Siopao,Bánh bao;Baozi|1;
empanada;Empanada;empanada / empanadilla;snacks;3;Empanada;Empanadas|2;
croquettes;Croquettes;croquetas;snacks;4;Croquette,Korokke;Croquettes|1,Croquetas,Korokke|1;
falafel;Falafel;falafel;snacks;3;Falafel;Falafel|1;
onion_rings;Onion rings;aros de cebolla;snacks;3;Onion ring;;
pakora;Pakora;pakora;snacks;3;Pakora,Bonda;Pakora|1;
tempura;Tempura;tempura;dishes;3;Tempura;Tempura|1;
arancini;Arancini;arancini;snacks;3;Arancini;Arancini|0;
bread_with_tomato;Bread with tomato / bruschetta;pan con tomate / bruschetta;snacks;3;Bruschetta;Pa amb tomàquet|1,Pan con tomate|1,Bruschetta|0;
canapes;Canapés;canapés / montaditos;snacks;3;Canapé,Cicchetti,Hors d'oeuvre,Pincho;;
dolma;Stuffed vine leaves;hojas de parra rellenas;dishes;3;Grape leaves;;
guacamole;Guacamole;guacamole;sauces;3;Guacamole;Guacamole|1;
salsa;Salsa / pico de gallo;salsa / pico de gallo;sauces;3;Salsa sauce,Pico de gallo;Pico de gallo,Salsa (sauce)|1;
tzatziki;Tzatziki;tzatziki;sauces;3;Tzatziki,Raita;Raita|1,Tzatziki|1;
mayonnaise;Mayonnaise / aioli;mayonesa / alioli;sauces;2;Mayonnaise,Aioli;Mayonnaise|1,Aioli|1;
pizza;Pizza;pizza;fast_food;3;Pizza,California-style pizza,Sicilian pizza,Pizza cheese;;
calzone;Calzone;calzone;fast_food;4;Calzone;Calzone|1;
hamburger;Hamburger;hamburguesa;fast_food;3;Hamburger,Cheeseburger,Slider,Buffalo burger,Veggie burger,Whopper,Burger king premium burgers,Jucy lucy,Salmon burger;;
sandwich;Sandwich;sándwich;fast_food;3;Sandwich,Ham and cheese sandwich,Melt sandwich,Blt,Breakfast sandwich,Egg sandwich,Panini,Bacon sandwich,Bologna sandwich,Muffuletta;;
baguette_sandwich;Baguette sandwich;bocadillo;fast_food;4;Submarine sandwich,Bocadillo,Bánh mì,Po' boy,Cheesesteak;Bocadillos|1;
wrap;Wrap / burrito;wrap / burrito;fast_food;3;Burrito,Mission burrito,Sandwich wrap,Wrap roti,Chimichanga;;
tacos;Tacos;tacos;fast_food;3;Taco,Korean taco,Tostada;;
quesadilla;Quesadilla;quesadilla;fast_food;3;Quesadilla;Quesadilla|1;
green_salad;Green salad;ensalada verde / mixta;dishes;3;Salad,Garden salad,Spinach salad;;
caesar_salad;Caesar salad;ensalada César;dishes;4;Caesar salad;;
greek_salad;Greek salad;ensalada griega;dishes;4;Greek salad,Israeli salad;;
caprese_salad;Caprese salad;ensalada caprese;dishes;4;Caprese salad;Insalata caprese|1;
pasta_salad;Pasta salad;ensalada de pasta;dishes;4;Pasta salad;Pasta salad|1;
potato_salad;Potato salad (ensaladilla);ensaladilla rusa;dishes;4;Potato salad;Olivier salad|1,Potato salads|1;
coleslaw;Coleslaw;ensalada de col;dishes;4;Coleslaw;Coleslaw|1;
fruit_salad;Fruit salad;macedonia;dishes;4;Fruit salad,Macedonia,Fruit cup;;
tabbouleh;Tabbouleh;tabulé;dishes;4;Tabbouleh;Tabbouleh|1;
soup;Soup / broth;sopa / caldo;dishes;3;Soup,Broth,Consommé;;
cream_soup;Cream soup;crema de verduras;dishes;4;Vichyssoise,Cream of mushroom soup,Bisque,Potage;Pumpkin soups|1;
tomato_soup;Tomato soup;sopa de tomate;dishes;4;Tomato soup;Tomato soup|1;
chicken_soup;Chicken soup;sopa de pollo;dishes;4;Chicken soup,Cock-a-leekie soup,Soto ayam;;
minestrone;Minestrone;minestrone;dishes;4;Minestrone;Minestrone|1;
french_onion_soup;French onion soup;sopa de cebolla;dishes;4;French onion soup;French onion soup|1,Onion soup|1;
miso_soup;Miso soup;sopa de miso;dishes;4;Miso soup;Miso soup|1;
clam_chowder;Clam chowder;crema de almejas;dishes;4;Clam chowder;Clam chowder|1;
noodle_soup;Noodle soup;sopa de fideos;dishes;4;Noodle soup,Beef noodle soup,Wonton noodles,Laksa,Udon,Soba,Okinawa soba,Kalguksu,Curry mee,Hae mee,Thukpa;;
ramen;Ramen;ramen;dishes;5;Ramen;Ramen|1;
pho;Pho;pho;dishes;5;Pho,Bún bò huế;Phos|1;
gazpacho;Gazpacho;gazpacho;dishes;5;Gazpacho;Gazpacho;
stew;Meat stew;estofado / guiso de carne;dishes;3;Stew,Irish stew,Goulash,Boeuf à la mode;Stews|1,Beef stews|1;
chili_con_carne;Chili con carne;chili con carne;dishes;4;Chili con carne;Chili con carne|1;
curry;Curry;curry;dishes;3;Curry,Red curry,Yellow curry,Thai curry,Japanese curry,Massaman curry,Korma,Vindaloo,Rice and curry,Karahi,Chicken tikka masala,Pasanda,Gosht;;
moussaka;Moussaka;musaka;dishes;4;Moussaka;Musaka|1;
lasagna;Lasagne;lasaña;dishes;5;Lasagne;;
spaghetti_bolognese;Spaghetti bolognese;espaguetis a la boloñesa;dishes;4;Bolognese sauce;;
carbonara;Pasta carbonara;pasta a la carbonara;dishes;4;Carbonara;Carbonara|1;
pasta_tomato;Pasta with tomato sauce;pasta con tomate;dishes;4;Pasta pomodoro,Arrabbiata sauce,Marinara sauce,Amatriciana sauce,Penne alla vodka,Vodka sauce,Spaghetti alla puttanesca;;
pesto_pasta;Pasta with pesto;pasta al pesto;dishes;4;Pesto;Pesto dishes|1;
mac_and_cheese;Macaroni and cheese;macarrones con queso;dishes;4;Macaroni and cheese;Macaroni and cheese|1;
ravioli;Ravioli / tortellini;raviolis / tortellini;dishes;4;Ravioli,Tortellini;Ravioli dishes|1,Tortellini|1;
cannelloni;Cannelloni;canelones;dishes;4;Cannelloni;Cannelloni|1,Cannelloni dishes,Canelones|1;
pad_thai;Pad thai;pad thai;dishes;4;Pad thai,Drunken noodles;;
bibimbap;Bibimbap;bibimbap;dishes;4;Bibimbap;Bibimbap|1;
hot_pot;Hot pot;hot pot;dishes;4;Hot pot,Nabemono;;
quiche;Quiche;quiche;dishes;4;Quiche,Quiche lorraine;Quiches|1;
pot_pie;Pot pie;pastel de carne;dishes;4;Pot pie,Meat and potato pie;Pot pies|1,Chicken pot pies|0;
gratin;Gratin / casserole;gratinado;dishes;3;Gratin,Casserole;Gratin|1,Casseroles|1;
mashed_potatoes;Mashed potatoes;puré de patatas;dishes;3;Mashed potato,Instant mashed potatoes;Mashed potatoes|1;
baked_potato;Baked potato;patata asada;dishes;3;Baked potato;Baked potatoes|1;
fried_potatoes;Sautéed potatoes;patatas salteadas;dishes;3;Home fries,Lyonnaise potatoes;Fried potatoes|1,Home fries|1,Papas arrugadas|0;
ratatouille;Ratatouille (pisto);pisto;dishes;4;Ratatouille,Pisto;Ratatouille|1,Pisto;
full_breakfast;Full English breakfast;desayuno inglés;dishes;4;Full breakfast;;
espresso;Espresso / black coffee;café solo;drinks;2;Espresso,Ristretto,Doppio,Lungo,Cuban espresso,Caffè americano,Americano;;
coffee_with_milk;Coffee with milk / cappuccino;café con leche / capuchino;drinks;2;Latte,Cappuccino,Flat white,Café au lait,Cortado,Caffè macchiato,White coffee,Galão,Wiener melange,Coffee milk,Mocaccino,Marocchino,Espressino;Café con leche;
iced_coffee;Iced coffee;café con hielo;drinks;3;Iced coffee,Frappé coffee,Vietnamese iced coffee;Iced coffee|1;
tea;Tea;té / infusión;drinks;2;Tea,Green tea,Earl grey tea,Masala chai,Assam tea,Darjeeling tea,Oolong,Pu'er tea;;
iced_tea;Iced tea;té helado;drinks;3;Iced tea;Iced tea|1;
hot_chocolate;Hot chocolate;chocolate caliente;drinks;3;Hot chocolate,Champurrado;Hot chocolate|1;
orange_juice;Orange juice;zumo de naranja;drinks;3;Orange juice;Orange juice|1;
juice;Fruit juice;zumo de frutas;drinks;2;Juice,Apple juice,Grape juice,Cranberry juice,Grapefruit juice,Pomegranate juice,Strawberry juice,Guava juice,Papaya juice,Passion fruit juice;;
smoothie;Smoothie;smoothie / batido de frutas;drinks;3;Smoothie,Health shake;Smoothies|1;
milkshake;Milkshake;batido;drinks;3;Milkshake;Milkshakes|1;
beer;Beer;cerveza;drinks;2;Beer,Lager,Ale,Wheat beer,Stout,Ice beer;;
red_wine;Red wine;vino tinto;drinks;2;Red wine;Glasses of red wine|1;
white_wine;White wine;vino blanco;drinks;2;White wine;White wine|1,Glasses of white wine|1;
sparkling_wine;Sparkling wine / cava;cava / champán;drinks;2;Champagne,Sparkling wine;;
cocktail;Cocktail;cóctel;drinks;2;Cocktail,Margarita,Piña colada,Cosmopolitan,Daiquiri,Martini,Caipirinha,Mai tai,Negroni,Spritz,Bloody mary,Mimosa,Bellini;;
mojito;Mojito;mojito;drinks;3;Mojito;Mojito|1;
gin_tonic;Gin and tonic;gin-tonic;drinks;3;Gin and tonic,Vodka and tonic;Gin and tonic;
sangria;Sangria / tinto de verano;sangría / tinto de verano;drinks;3;Sangria,Tinto de verano,Kalimotxo;Sangria|1,Tinto de verano;
vermouth;Vermouth;vermut;drinks;3;Vermouth;Vermouth|1;
spirits;Spirits (whisky, brandy);licores (whisky, coñac);drinks;2;Whisky,Bourbon whiskey,Brandy,Blended whiskey,Liqueur,Distilled beverage;;
cola;Cola;refresco de cola;drinks;3;Cola,Coca-cola;;
lemonade;Lemonade;limonada;drinks;2;Lemonade,Limeade,Limonana;Lemonade|1;
horchata;Horchata;horchata;drinks;3;Horchata;Horchata|1;
cider;Cider;sidra;drinks;2;Cider;;
kumquat;Kumquat;kumquat;fruit;1;Kumquat;;
jackfruit;Jackfruit;yaca;fruit;1;Jackfruit;Jackfruit|1;
durian;Durian;durián;fruit;1;Durian;Durian|1;food
pickles;Pickles;encurtidos / pepinillos;vegetables;2;Pickled cucumber,Pickling;Pickles|1,Banderillas|0;
seaweed;Seaweed;algas;vegetables;1;Nori,Wakame,Laver;Seaweed salad|1,Seaweed as food|1,Seaweed dishes|1,Nori|1;
garlic_bread;Garlic bread;pan de ajo;grains;3;Garlic bread;Garlic bread|1;
pastries;Pastries;bollería;sweets;1;Pastry,Viennoiserie,Pâtisserie,Puff pastry;;
mille_feuille;Mille-feuille;milhojas;sweets;3;Mille-feuille;Mille-feuille|1,Milhojas|0;
fruitcake;Fruit cake / panettone;panettone / plum cake;sweets;4;Fruitcake,Panettone,Stollen;Panettone|1;
loaf_cake;Banana bread / loaf cake;bizcocho de plátano;sweets;4;Banana bread,Pumpkin bread;Banana bread|1;
swiss_roll;Swiss roll;brazo de gitano;sweets;4;Swiss roll,Roulade;Swiss rolls|1,Brazo de gitano;
fritters;Fritters (buñuelos);buñuelos;sweets;3;Buñuelo,Beignet,Zeppole,Loukoumades;Buñuelos|1,Beignets|1;
potato_pancakes;Potato pancakes / hash browns;tortitas de patata;dishes;3;Potato pancake,Hash browns;Potato pancakes|1,Hash browns|1,Rösti|1,Latkes|1;
shepherds_pie;Shepherd's pie;pastel de carne y puré;dishes;4;Cottage pie;Shepherd's pie|1,Cottage pie|1;
fish_soup;Fish soup;sopa de pescado;dishes;4;Bouillabaisse;Fish soup|1;
soft_drink;Soft drink;refresco;drinks;2;Soft drink,Carbonated soft drinks,Orange soft drink,Lemon-lime,Root beer,Ginger ale;;food
"""


def _parse():
    out = []
    for line in _TABLE.strip().splitlines():
        f = [x.strip() for x in line.split(";")]
        assert len(f) == 8, line
        cid, en, es, group, rank, oi, wm, flags = f
        out.append({"id": cid, "en": en, "es": es, "group": group, "rank": int(rank),
                    "open_images": [x.strip() for x in oi.split(",") if x.strip()],
                    "wikimedia": [x.strip() for x in wm.split(",") if x.strip()],
                    "need_food": "food" in flags.split(",")})
    ids = [c["id"] for c in out]
    assert len(ids) == len(set(ids)), "duplicate ids"
    return out


CLASSES = _parse()
NOT_FOOD = "_not_food"
GROUPS = {"fruit", "vegetables", "grains", "legumes", "meat", "fish", "eggs", "dairy", "fats_nuts", "sweets",
          "drinks", "dishes", "fast_food", "snacks", "sauces"}
assert all(c["group"] in GROUPS for c in CLASSES)
