# json-filter

High-speed stream-based filtering of arbitrarily large JSON documents.

This library takes extraordinary pains to insure that Java objects are not created unnecessarily while processing
the input and output streams. 

Applications:
* Redacting content that client is not allowed to see
* Reducing the size of large JSON before parsing using a library like Jackson 


## Usage

Filtering a JSON stream is very easy:

```java
// create input and output streams
JsonReader reader = new JsonReader(<instance of `java.io.Reader`>)
JsonWriter writer = new JsonWriter(<instance of 'java.io.Writer`>)

// configure filter
JsonFilterOptions opts = ImmutableJsonFilterOptions.builder() //
  .addExcludes("some/path/to/exclude") //
  .pretty(true) //
  .build();
  
// execute filter
JsonFilter filter = new JsonFilter(reader,writer,options);
filter.process();
```

## Paths

Paths are used to identify portions of the JSON to be included or excluded.  The forward slash (`/`) character
can be used in a path to separate nested JSON elements.

For example, consider the following JSON:

```java
{
  "store": {
    "book": [
      {
        "category": "reference",
        "author": "Nigel Rees",
        "title": "Sayings of the Century",
        "price": 8.95
      },
      {
        "category": "fiction",
        "author": "Evelyn Waugh",
        "title": "Sword of Honour",
        "price": 12.99
      },
      {
        "category": "fiction",
        "author": "Herman Melville",
        "title": "Moby Dick",
        "isbn": "0-553-21311-3",
        "price": 8.99
      },
      {
        "category": "fiction",
        "author": "J. R. R. Tolkien",
        "title": "The Lord of the Rings",
        "isbn": "0-395-19395-8",
        "price": 22.99
      }
    ],
    "bicycle": {
      "color": "red",
      "price": 19.95
    }
  },
  "expensive": 10
}
```

The following paths could be constructed:
* `expensive` would resolve to a single node with a value of `10`
* `store/book/category` would resolve to 4 nodes with values of `reference` and `fiction`.
* `store/bicycle/color` would resolve to a single node with a value of `19.95`. 


## Installation

The library is available on [Maven Central](https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.arakelian%22%20AND%20a%3A%22json-filter%22)

### Maven

Add the following to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>central</id>
        <name>Central Repository</name>
        <url>http://repo.maven.apache.org/maven2</url>
        <releases>
            <enabled>true</enabled>
        </releases>
    </repository>
</repositories>

...

<dependency>
    <groupId>com.arakelian</groupId>
    <artifactId>json-filter</artifactId>
    <version>1.7.1</version>
    <scope>compile</scope>
</dependency>
```

### Gradle

Add the following to your `build.gradle`:

```groovy
repositories {
  mavenCentral()
}

dependencies {
  compile 'com.arakelian:json-filter:1.7.1'
}
```

## Licence

Apache Version 2.0
