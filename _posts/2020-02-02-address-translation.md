---
title: O translacji adresów
layout: post
tags: [paging, hostbridge, hardware, x64, linux]
---

Dlaczego nie da się łatwo stworzyć programu, który zmodyfikuje wybraną komórkę pamięci, czyli o przestrzeniach adresowych, translacjach adresów, dziurach w pamięci i urządzeniach, które udają urządzenia PCI, chociaż nimi nie są.

Chociaż typowy komputer może być wyposażony w wiele modułów pamięci RAM, a jeden moduł zwykle zawiera wiele odrębnych banków pamięci o pewnej liczbie wierszy i kolumn, których przecięcia wyznaczają pojedyncze bity, można patrzeć na całość jak na sekwencję komórek o swobodnym dostępie. Czy gdybyśmy znali schemat, według którego są ponumerowane bity konkretnego modelu pamięci, to czy bylibyśmy w stanie wskazać "palcem", do których z nich odwołuje się dana instrukcja w programie?

Zacznijmy od bardzo prostego kodu, który modyfikuje bajt dynamicznie zaalokowanej pamięci, a następnie wypisuje na ekran jego adres:
```c++
#include<cstdint>
#include<cstdio>
#include<cstring>

int main() 
{
  uint8_t* addr = new uint8_t;
  *addr = 1;
  printf("Address: %llx\n", (uint64_t) addr);
  return 0; 
}
```
```console
$ g++ -O2 -o address-translation address-translation.cpp
$ ./address-translation
Address: 557e6d063eb0
```
Czy wypisana wartość może nam coś powiedzieć na temat fizycznej lokalizacji zmienionych komórek pamięci?

## Pamięć wirtualna
Rolę pierwszej kłody pod nogami odgrywa mechanizm pamięci wirtualnej, który sprawia, że każdy proces posiada swój własny świat, do którego pozostałe procesy nie mają wstępu bez wyraźnego zaproszenia. Proces _A_ nie może modyfikować pamięci procesu _B_, ponieważ operują one na niezależnych, wirtualnych przestrzeniach adresowych, które są odwzorowywane na rozłączne obszary fizycznej przestrzeni adresowej. Adres _X_ w kontekście procesu _A_ prowadzi do innych komórek pamięci niż ten sam adres _X_ w kontekście procesu _B_:

![Pamięć wirtualna](/assets/img/virtmemory.png)

W proces tłumaczenia adresów wirtualnych na adresy fizyczne zaangażowany jest z jednej strony system operacyjny, który musi przygotować odpowiedni "słownik", a z drugiej procesor, który, wykorzystując ten słownik, tłumaczy w sposób przezroczysty dla aplikacji jej odwołania do pamięci.

W architekturze x64 zarówno wirtualna, jak i fizyczna przestrzeń adresowa jest podzielona na bloki o stałym rozmiarze 4kB zwane odpowiednio stronami i ramkami, a wspomniany słownik (oparty na 4-poziomowej strukturze) służy do odwzorowywania stron na ramki. Proces tłumaczenia adresów wirtualnych na fizyczne z użyciem tej struktury jest przedstawiony poniżej:

![Tłumaczenie adresów](/assets/img/translation.png)

Adres wirtualny składa się z 48 bitów, które można podzielić na 4 9-bitowe indeksy (`ID1` - `ID4`) i 12-bitowe przesunięcie (_ang. offset_). Rejestr procesora `CR3` zawiera adres tablicy, której `ID1`-ty element prowadzi do tablicy kolejnego poziomu, której `ID2`-ty element prowadzi do jeszcze jednej tablicy, której `ID3`-ty element prowadzi do ostatniej tablicy, której `ID4`-ty element zawiera adres ramki, z którego można już łatwo zbudować wynikowy adres fizyczny poprzez dodanie offsetu z początkowego adresu wirtualnego.

Czasami ten proces przechodzenia przez drzewo tablic może skończyć się przed dotarciem do ostatniego poziomu, np. gdy większy obszar wirtualnej przestrzeni adresowej nie ma reprezentacji w pamięci fizycznej, albo w wyniku użycia mechanizmu _huge pages_, którego nie będę opisywać szczegółowo w tym artykule.

Tak się składa, że w linuksie można całkiem łatwo poznać szczegóły dotyczące stron pamięci wybranego procesu, w tym odpowiadające im numery fizycznych ramek, korzystając z pliku `/proc/<pid>/pagemap`. Opis jego struktury znajduje się na przykład [tu][1]. Dzięki temu możemy rozszerzyć poprzedni kod o wypisywanie adresu fizycznego modyfikowanego obszaru pamięci (dla zwiększenia czytelności także w formie zaokrąglonej do 1MB):
```c++
#include<cstdint>
#include<cstdio>
#include<cstring>
#include<unistd.h>

uint64_t virtToPhys(uint64_t virtualAddr)
{
  constexpr size_t PAGESIZE = 4096;

  uint64_t physicalFrame = 0;
  FILE* pagemap = fopen("/proc/self/pagemap", "rb");
  fseek(pagemap, virtualAddr / PAGESIZE * 8, SEEK_SET);
  fread(&physicalFrame, 8, 1, pagemap);
  fclose(pagemap);
  // Jeśli bit 63 jest ustawiony, to bity 0-54 zawierają numer fizycznej 
  // ramki pamięci, odpowiadającej badanej stronie.
  if (!(physicalFrame >> 63))
    return 0;
  physicalFrame &= (1ULL << 55) - 1;
  return physicalFrame * PAGESIZE + virtualAddr % PAGESIZE;
}

int main() 
{
  uint8_t* addr = new uint8_t;
  *addr = 1;
  const auto physAddr = virtToPhys((uint64_t) addr);
  printf("Address: %llx\n", (uint64_t) addr);
  printf("Physical address: %llx ~%uM\n", physAddr, physAddr >> 20);
  return 0; 
}
```

Dostęp do plików `pagemap` wymaga uprawnień administratora, więc może być potrzebne uruchomienie programu z `sudo`.

```console
$ sudo ./address-translation
Address: 5629a80b9eb0
Physical address: 19829ceb0 ~6530M
```

## Dziury w pamięci, host bridge i remapping

To jednak nie koniec. Okazuje się, że przy odpowiedniej liczbie powtórzeń powyższy kod może wypisać podejrzanie dużą wartość, która nie może być po prostu numerem bajtu w module pamięci. Przykładowo, na moim komputerze z 8GB RAM dostałem informację o modyfikacji obszaru o adresie fizycznym ~8702M:

```console
$ sudo ./address-translation
Address: 55b5ec43ceb0
Physical address: 21fec8eb0 ~8702M
```

Wyjaśnienie tej zagadki ma pewien związek z moim poprzednim artykułem, gdzie wspomniałem, że można dostać się do przestrzeni konfiguracyjnej urządzeń PCIe, zwyczajnie czytając dane z odpowiednich zakresów fizycznej przestrzeni adresowej. Ogólnie rzecz biorąc, nie wszystkie adresy fizyczne prowadzą do pamięci RAM. Pewne zakresy mogą być przeznaczone na dostęp do pamięci ROM, czy urządzeń PCIe.
Czy to znaczy, że gdy pewien adres prowadzi do pamięci ROM, to komórka pamięci RAM o tym samym numerze jest stracona i nie można jej użyć?

Choć to, o czym zaraz napiszę wygląda podobnie albo tak samo we wszystkich współczesnych procesorach Intel, to w teorii pewne szczegóły mogą się różnić pomiędzy generacjami, więc dla ścisłości będę opierać się na specyfikacji dot. mojego procesora: ["2nd Generation... Datasheet, vol. 2"][2].

Okazuje się, że największa "dziura" w fizycznej przestrzeni adresowej (ofiara złożona urządzeniom PCIe) zaczyna się w okolicy 3GB, w miejscu zwanym `TOLUD` (_top of low usable DRAM_), a kończy dokładnie na 4GB. Strata ~1GB pamięci w taki sposób byłaby dotkliwa, dlatego procesory udostępniają mechanizm, pozwalający na zdefiniowanie zakresu adresów fizycznych: `<REMAPBASE, REMAPLIMIT>` powyżej faktycznego rozmiaru modułów RAM, który zostanie przetłumaczony na zakres komórek pamięci o numerach: `<TOLUD, TOLUD + REMAPLIMIT - REMAPBASE>` (tzw. _memory remapping_).

Komponentem, który realizuje to tłumaczenie jest _Host bridge_, obecnie zintegrowany z procesorem, a dawniej znany jako mostek północny na płycie głównej. _Host bridge_ jest widziany w systemie jako urządzenie PCIe o adresie `00:00.0`, więc możemy zrzucić jego przestrzeń konfiguracyjną, używając standardowych narzędzi:

![Tłumaczenie adresów](/assets/img/remapbase.png)

Opierając się na specyfikacji, możemy się dowiedzieć, że:
* `REMAPBASE` jest zakodowany w 64-bitowym rejestrze 90h, z którego należy wziąć bity 35:20 i założyć, że bity 19:0 to same zera. Stąd `REMAPBASE=0x1ff800000 (~8184MB)`;
* `REMAPLIMIT` jest zakodowany w 64-bitowym rejestrze 98h, z którego należy wziąć bity 35:20 i założyć, że bity 19:0 to same jedynki. Stąd `REMAPLIMIT=0x24fdfffff (~9469MB)`;
* `TOLUD` jest zakodowany w 32-bitowym rejestrze BCh, z którego należy wziąć bity 31:20 i założyć, że bity 19:0 to same zera. Stąd `TOLUD=0xafa00000 (~2810MB)`.

Wartości te są konfigurowane przez firmware komputera. Możemy przekonać się o poprawności naszych wyliczeń zaglądając do pliku `/proc/iomem`, zawierającego mapę fizycznej przestrzeni adresowej. Ostatni zakres adresów, prowadzący do pamięci RAM, kończy się dokładnie w miejscu zdefiniowanym przez rejestr `REMAPLIMIT`:

```console
$ sudo grep 'System RAM' /proc/iomem | tail -n1
100000000-24fdfffff : System RAM
```

[1]: https://www.kernel.org/doc/Documentation/vm/pagemap.txt
[2]: https://www.intel.com/content/dam/www/public/us/en/documents/datasheets/2nd-gen-core-desktop-vol-2-datasheet.pdf